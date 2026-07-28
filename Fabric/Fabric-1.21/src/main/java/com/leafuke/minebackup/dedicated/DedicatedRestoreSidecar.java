package com.leafuke.minebackup.dedicated;

import com.leafuke.minebackup.knotlink.protocol.KnotLinkCodec;
import com.leafuke.minebackup.knotlink.protocol.KnotLinkRequest;
import com.leafuke.minebackup.knotlink.protocol.KnotLinkResponse;
import com.leafuke.minebackup.knotlink.sdk.OpenSocketQuerier;
import com.leafuke.minebackup.knotlink.sdk.SignalSubscriber;
import com.leafuke.minebackup.runtime.WorldReleaseProbe;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pure-JDK restore handoff process. This class must not reference Fabric or
 * MinecraftClient classes because it runs after the server JVM starts shutting down.
 */
public final class DedicatedRestoreSidecar {
    private static final String HOST = "127.0.0.1";
    private static final String APP_ID = "0x00000020";
    private static final String OPEN_SOCKET_ID = "0x00000010";
    private static final String SIGNAL_ID = "0x00000020";

    private DedicatedRestoreSidecar() {
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("MineBackup sidecar requires the restart session directory");
            System.exit(2);
        }
        DedicatedRestoreStore store = new DedicatedRestoreStore(Path.of(args[0]));
        try {
            DedicatedRestoreSession session = store.readActive().orElseThrow(
                    () -> new IllegalStateException("No active MineBackup restart session"));
            run(store, session);
        } catch (Exception exception) {
            exception.printStackTrace(System.err);
            System.exit(1);
        }
    }

    static void run(DedicatedRestoreStore store, DedicatedRestoreSession original) throws Exception {
        SidecarSignalTracker signals =
                new SidecarSignalTracker(original.requestId(), original.worldId());
        AtomicBoolean disconnected = new AtomicBoolean();

        try (SignalSubscriber subscriber =
                     new SignalSubscriber(APP_ID, SIGNAL_ID, HOST, 6372, 5_000)) {
            subscriber.setSignalListener(payload -> {
                try {
                    Map<String, String> fields = KnotLinkCodec.parse(payload);
                    signals.accept(fields);
                } catch (Exception exception) {
                    disconnected.set(true);
                }
            });
            subscriber.setDisconnectListener(cause -> disconnected.set(true));
            subscriber.start();

            store.writeActive(original.withState(DedicatedRestoreSession.State.READY, ""));
            store.markReady();
            DedicatedRestoreSession waiting =
                    original.withState(DedicatedRestoreSession.State.WAITING_FOR_RELEASE, "");
            store.writeActive(waiting);

            long deadline = System.nanoTime()
                    + Duration.ofSeconds(original.operationTimeoutSeconds()).toNanos();
            long releaseDeadline = System.nanoTime()
                    + Duration.ofSeconds(original.worldReleaseTimeoutSeconds()).toNanos();
            ReleaseGate releaseGate = new ReleaseGate(3);
            boolean released = false;
            while (!released) {
                if (disconnected.get()) {
                    uncertain(store, original, "KnotLink signal channel disconnected before release");
                    return;
                }
                if (System.nanoTime() >= deadline || System.nanoTime() >= releaseDeadline) {
                    uncertain(store, original, "Timed out waiting for world files to be released");
                    return;
                }
                boolean parentExited = ProcessHandle.of(original.parentPid())
                        .map(handle -> !handle.isAlive())
                        .orElse(true);
                released = releaseGate.observe(
                        parentExited,
                        WorldReleaseProbe.isReleased(original.worldPath()));
                Thread.sleep(100L);
            }

            KnotLinkResponse release;
            try {
                release = query(
                        KnotLinkRequest.command("WORLD_SAVE_AND_EXIT_COMPLETE")
                                .conversation(original.requestId()));
            } catch (Exception exception) {
                uncertain(store, original, "World release acknowledgement failed: " + exception.getMessage());
                return;
            }
            if (!release.isOk()) {
                uncertain(store, original, "Backend rejected world release: " + release.displayMessage());
                return;
            }
            store.writeActive(original.withState(
                    DedicatedRestoreSession.State.RELEASE_ACKNOWLEDGED, ""));

            while (signals.terminal().isEmpty()) {
                if (disconnected.get() || System.nanoTime() >= deadline) {
                    uncertain(store, original, "No explicit restore terminal signal");
                    return;
                }
                Thread.sleep(100L);
            }

            SidecarSignalTracker.Outcome outcome = signals.terminal().orElseThrow();
            DedicatedRestoreSession.State outcomeState = switch (outcome) {
                case SUCCESS -> DedicatedRestoreSession.State.RESTORE_SUCCEEDED;
                case FAILURE -> DedicatedRestoreSession.State.RESTORE_FAILED;
                case CANCELLED -> DedicatedRestoreSession.State.RESTORE_CANCELLED;
            };
            store.writeActive(original.withState(outcomeState, ""));
            try {
                waitForParentExit(original.parentPid(), deadline);
            } catch (IllegalStateException exception) {
                uncertain(store, original, exception.getMessage());
                return;
            }

            try {
                new ProcessBuilder(original.restartCommand())
                        .directory(original.workingDirectory().toFile())
                        .inheritIO()
                        .start();
            } catch (Exception exception) {
                DedicatedRestoreSession failed = original.withState(
                        DedicatedRestoreSession.State.RESTART_FAILED,
                        exception.getMessage());
                store.writeFinal(failed);
                if (signals.rejoinRequested()) {
                    sendRejoin(original.requestId(), false, "restart script failed");
                }
                return;
            }

            DedicatedRestoreSession.State finalState = switch (outcome) {
                case SUCCESS -> DedicatedRestoreSession.State.RESTART_STARTED;
                case FAILURE -> DedicatedRestoreSession.State.RESTORE_FAILED;
                case CANCELLED -> DedicatedRestoreSession.State.RESTORE_CANCELLED;
            };
            DedicatedRestoreSession started = original.withState(finalState, "restart script started");
            store.writeFinal(started);
            if (outcome == SidecarSignalTracker.Outcome.SUCCESS) {
                while (!signals.rejoinRequested()
                        && System.nanoTime() < deadline
                        && !disconnected.get()) {
                    Thread.sleep(50L);
                }
                if (signals.rejoinRequested()) {
                    sendRejoin(original.requestId(), true, "");
                }
            }
        }
    }

    private static void waitForParentExit(long pid, long deadline) throws InterruptedException {
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("Parent server JVM did not exit before timeout");
            }
            Thread.sleep(100L);
        }
    }

    private static void uncertain(
            DedicatedRestoreStore store,
            DedicatedRestoreSession session,
            String detail) throws Exception {
        store.writeFinal(session.withState(DedicatedRestoreSession.State.UNCERTAIN, detail));
    }

    private static void sendRejoin(UUID requestId, boolean success, String error) throws Exception {
        query(rejoinRequest(requestId, success, error));
    }

    static KnotLinkRequest rejoinRequest(UUID requestId, boolean success, String error) {
        KnotLinkRequest request = KnotLinkRequest.command("REJOIN_RESULT")
                .conversation(requestId)
                .field("result", success ? "success" : "failure");
        if (!error.isBlank()) {
            request.field("reason", error);
        }
        return request;
    }

    private static KnotLinkResponse query(KnotLinkRequest request) throws Exception {
        try (OpenSocketQuerier querier =
                     new OpenSocketQuerier(APP_ID, OPEN_SOCKET_ID, HOST, 6376, 5_000, 1_048_576)) {
            String payload = querier.query(request.serialize(), 5_000, java.util.concurrent.TimeUnit.MILLISECONDS);
            return KnotLinkResponse.parse(payload);
        }
    }

}
