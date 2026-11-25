package com.leafuke.minebackup.dedicated;

import static com.leafuke.minebackup.interop.InteropWire.*;

import com.google.gson.JsonObject;
import com.leafuke.minebackup.interop.*;
import com.leafuke.minebackup.runtime.WorldReleaseProbe;

import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Standalone child. Only the prepare/commit owner and a live open grant may start a script. */
public final class InteropRestoreSidecar {
    private InteropRestoreSidecar() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected a handoff directory");
        Path dir = Path.of(args[0]);
        try (var channel =
                        FileChannel.open(
                                dir.resolve("sidecar.lock"),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE);
                var ownership = channel.tryLock()) {
            if (ownership == null) return;
            var data = InteropFiles.readObject(dir.resolve("handoff.json"));
            try {
                run(dir, data);
            } catch (Exception error) {
                result(dir, data, "unconfirmed");
            }
        }
    }

    private static void run(Path dir, JsonObject h) throws Exception {
        UUID op = uuid(h, "operationId"),
                instance = uuid(h, "instanceId"),
                session = uuid(h, "sessionId");
        UUID domain = uuid(h, "domainId"),
                release = uuid(h, "stepId"),
                handoff = uuid(h, "handoffId");
        Path world = Path.of(string(h, "world"));
        long parent = integer(h, "pid", 1, Long.MAX_VALUE);
        long deadline =
                System.nanoTime()
                        + TimeUnit.SECONDS.toNanos(integer(h, "timeout", 1, Integer.MAX_VALUE));
        // A read on the exact original route confirms the sidecar can follow this operation.
        var snap = snapshot(h, instance, op, session);
        if (snap.step() == null
                || !snap.step().id().equals(release)
                || !snap.step().kind().equals("saveAndExit"))
            throw new IllegalStateException("lost release step");
        InteropFiles.writeObject(dir.resolve("ready.json"), object("handoffId", handoff));
        long releaseDeadline =
                System.nanoTime()
                        + TimeUnit.SECONDS.toNanos(
                                integer(h, "releaseTimeout", 1, Integer.MAX_VALUE));
        ReleaseGate gate = new ReleaseGate(3);
        while (true) {
            if (System.nanoTime() >= releaseDeadline)
                throw new IllegalStateException("world_release_timeout");
            boolean parentExited = ProcessHandle.of(parent).map(p -> !p.isAlive()).orElse(true);
            if (parentExited && !Files.exists(dir.resolve("committed.json")))
                throw new IllegalStateException("parent did not commit");
            if (parentExited
                    && !uuid(InteropFiles.readObject(dir.resolve("committed.json")), "handoffId")
                            .equals(handoff)) throw new IllegalStateException("wrong handoff");
            if (gate.observe(parentExited, parentExited && WorldReleaseProbe.isReleased(world)))
                break;
            Thread.sleep(100);
        }
        var released =
                object(
                        "sessionId",
                        session,
                        "operationId",
                        op,
                        "stepId",
                        release,
                        "verb",
                        "ack",
                        "outcome",
                        "released",
                        "handoffId",
                        handoff);
        boolean releaseKnown = false;
        while (System.nanoTime() < deadline) {
            try {
                if (!releaseKnown) {
                    try {
                        call(instance, "step", released, 2000);
                        releaseKnown = true;
                    } catch (Rejection e) {
                        if (!Set.of("step_expired", "step_conflict").contains(e.code())) throw e;
                    }
                }
                snap = snapshot(h, instance, op, session);
                var step = snap.step();
                if (step != null
                        && step.kind().equals("open")
                        && "eligible".equals(snap.reopen())
                        && !snap.recoveryPending()) {
                    var request =
                            object(
                                    "sessionId",
                                    session,
                                    "operationId",
                                    op,
                                    "stepId",
                                    step.id(),
                                    "verb",
                                    "claim",
                                    "handoffId",
                                    handoff);
                    long sent = System.nanoTime();
                    var grant = call(instance, "step", request, 2000);
                    if (!uuid(grant, "stepId").equals(step.id())
                            || !string(grant, "kind").equals("open"))
                        throw new IllegalStateException("wrong_grant");
                    long expiry =
                            sent
                                    + TimeUnit.MILLISECONDS.toNanos(
                                            integer(grant, "remainingMs", 1, Integer.MAX_VALUE));
                    try (var guard = WorldAdmission.enter(world, op, domain)) {
                        if (System.nanoTime() >= expiry || !WorldReleaseProbe.isReleased(world))
                            throw new IllegalStateException("world_in_use");
                        // Exclusive sidecar.lock spans this check, durable transition and process
                        // creation.
                        if (Files.exists(dir.resolve("launch.json"))) {
                            result(dir, h, "unconfirmed");
                            return;
                        }
                        InteropFiles.writeObject(
                                dir.resolve("launch.json"),
                                object("handoffId", handoff, "stepId", step.id()));
                        List<String> cmd = new ArrayList<>();
                        h.getAsJsonArray("command").forEach(s -> cmd.add(s.getAsString()));
                        String outcome;
                        try {
                            if (System.nanoTime() >= expiry)
                                throw new IllegalStateException("step_expired");
                            new ProcessBuilder(cmd)
                                    .directory(Path.of(string(h, "workingDirectory")).toFile())
                                    .inheritIO()
                                    .start();
                            outcome = "scriptStarted";
                        } catch (Exception failure) {
                            outcome = "failed";
                        }
                        result(dir, h, outcome);
                        request.addProperty("verb", "ack");
                        request.addProperty("outcome", outcome);
                        while (System.nanoTime() < deadline) {
                            try {
                                call(instance, "step", request, 2000);
                                break;
                            } catch (Rejection rejected) {
                                break;
                            } catch (Exception lost) {
                                Thread.sleep(1500);
                            }
                        }
                        return;
                    }
                }
                if (snap.state().equals("unresolved")
                        || snap.state().equals("finished") && "blocked".equals(snap.reopen())) {
                    result(dir, h, "unconfirmed");
                    return;
                }
            } catch (InteropWire.Rejection refused) {
                if (refused.code().equals("backend_offline")) {
                    var next = successor(h, instance);
                    if (next != null) {
                        instance = next;
                        // Never ACK the former instance's release into a new process. Recover by
                        // reading only.
                        releaseKnown = true;
                    }
                }
                if (!Set.of("result_unknown", "backend_offline").contains(refused.code()))
                    throw refused;
            } catch (java.io.IOException | java.util.concurrent.TimeoutException unavailable) {
                /* read again only */
            }
            Thread.sleep(1000);
        }
        result(dir, h, "unconfirmed");
    }

    private static UUID successor(JsonObject h, UUID original) throws Exception {
        UUID domain = uuid(h, "domainId");
        var candidates =
                InteropFiles.endpoints(InteropFiles.root().resolve("endpoints")).stream()
                        .filter(
                                e ->
                                        e.domain().equals(domain)
                                                && e.product()
                                                        .equals(h.get("product").getAsString())
                                                && !e.instance().equals(original))
                        .toList();
        if (candidates.size() != 1) return null;
        var candidate = candidates.getFirst();
        JsonObject hello = call(candidate.instance(), "hello", object(), 2000);
        if (!uuid(hello, "domainId").equals(domain)
                || !string(hello, "product").equals(candidate.product())
                || integer(hello, "protocolMajor", 3, 3) != 3) return null;
        return candidate.instance();
    }

    private static InteropSnapshot snapshot(JsonObject h, UUID instance, UUID op, UUID session)
            throws Exception {
        var snap =
                InteropSnapshot.parse(
                        call(
                                instance,
                                "get",
                                object("operationId", op, "sessionId", session),
                                2000));
        if (!snap.operation().equals(op)
                || !snap.kind().equals("restore")
                || !snap.target().equals(string(h, "targetRef"))
                || snap.step() != null
                        && (!snap.step().session().equals(session)
                                || !snap.step().target().equals(snap.target())))
            throw new IllegalStateException("stale_handoff");
        return snap;
    }

    static void result(Path dir, JsonObject h, String outcome) throws Exception {
        Path file = dir.resolve("result.json");
        if (Files.exists(file)) {
            var existing = InteropFiles.readObject(file);
            if (!uuid(existing, "operationId").equals(uuid(h, "operationId"))
                    || !uuid(existing, "handoffId").equals(uuid(h, "handoffId")))
                throw new IllegalStateException("different_handoff_owner");
            String known = string(existing, "outcome");
            if (known.equals("scriptStarted") || known.equals("failed")) return;
        }
        InteropFiles.writeObject(
                dir.resolve("result.json"),
                object(
                        "operationId",
                        uuid(h, "operationId"),
                        "handoffId",
                        uuid(h, "handoffId"),
                        "outcome",
                        outcome));
    }
}
