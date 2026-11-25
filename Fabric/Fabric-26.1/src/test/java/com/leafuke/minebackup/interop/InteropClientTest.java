package com.leafuke.minebackup.interop;

import static com.leafuke.minebackup.interop.InteropWire.*;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

class InteropClientTest {
    @TempDir Path temp;
    final UUID domain = UUID.randomUUID(),
            instance = UUID.randomUUID(),
            binding = UUID.randomUUID();
    final AtomicInteger writes = new AtomicInteger(), reads = new AtomicInteger();
    final BlockingQueue<InteropSnapshot> observed = new LinkedBlockingQueue<>();
    volatile JsonObject checkpoint;
    volatile Consumer<String> signal;
    volatile UUID session;

    private java.util.function.Function<InteropSnapshot,InteropSnapshot.Ack> effect=s->{
        throw new AssertionError("No steps expected");
    };
    private InteropClient client(InteropClient.Transport transport) throws Exception {
        Path endpoints = Files.createDirectory(temp.resolve("endpoints"));
        InteropFiles.writeObject(
                endpoints.resolve(instance + ".json"),
                object(
                        "format",
                        1,
                        "protocolMajor",
                        3,
                        "product",
                        "minebackup",
                        "instanceId",
                        instance,
                        "domainId",
                        domain,
                        "label",
                        "test"));
        return new InteropClient(
                endpoints,
                temp.resolve("state"),
                transport,
                (e, w, s, disconnect) -> {
                    signal = s;
                    session = w.session();
                    return new InteropClient.Subscription() {
                        public boolean connected() {
                            return true;
                        }

                        public void close() {}
                    };
                },
                new InteropClient.Listener() {
                    public void snapshot(InteropSnapshot s, boolean visible) {
                        observed.add(s);
                    }

                    public void unconfirmed(UUID op, boolean restore) {}

                    public CompletionStage<InteropSnapshot.Ack> perform(
                            InteropClient.Binding b,
                            InteropSnapshot s,
                            long expiry,
                            BooleanSupplier live) {
                        return CompletableFuture.completedFuture(effect.apply(s));
                    }
                });
    }

    private JsonObject business(UUID inst, String type, JsonObject body, int timeout)
            throws Exception {
        assertEquals(instance, inst);
        switch (type) {
            case "hello":
                {
                    var caps = new JsonArray();
                    caps.add("backup");
                    caps.add("catalog");
                    return object(
                            "product",
                            "minebackup",
                            "domainId",
                            domain,
                            "protocolMajor",
                            3,
                            "minecraftInterop",
                            "ready",
                            "pluginState",
                            "notApplicable",
                            "admissionRef",
                            "window",
                            "admissionTtlMs",
                            120000,
                            "capabilities",
                            caps);
                }
            case "match":
                {
                    var targets = new JsonArray();
                    targets.add(object("targetRef", "target", "scope", "world", "label", "test"));
                    return object("targets", targets);
                }
            case "bind":
                return object("bindingId", binding, "targetRef", "target");
            case "sync":
                return object("operations", new JsonArray());
            case "leave":
                return object();
            case "submit":
                {
                    writes.incrementAndGet();
                    checkpoint =
                            object(
                                    "operationId",
                                    uuid(body, "operationId"),
                                    "kind",
                                    "backup",
                                    "targetRef",
                                    "target",
                                    "rev",
                                    1,
                                    "state",
                                    "queued");
                    throw new TimeoutException("lost ACK");
                }
            case "get":
                {
                    reads.incrementAndGet();
                    return checkpoint.deepCopy();
                }
            default:
                throw new AssertionError(type);
        }
    }

    @Test
    void lostAckUsesExistingReceiptAndDropsForeignAndOlderHints() throws Exception {
        UUID id = UUID.randomUUID();
        try (var link = client(this::business)) {
            link.enter(Files.createDirectory(temp.resolve("world")), false);
            for (int i = 0; i < 100 && link.binding() == null; i++) Thread.sleep(20);
            assertNotNull(link.binding());
            var result = link.submit(id, "backup", null, object()).get(5, TimeUnit.SECONDS);
            assertEquals("queued", string(result, "state"));
            assertEquals(1, writes.get());
            assertTrue(reads.get() >= 1);
            assertEquals("queued", observed.poll(3, TimeUnit.SECONDS).state());
            assertTrue(Files.exists(temp.resolve("state/pending/" + id + ".json")));

            var completed = checkpoint.deepCopy();
            completed.addProperty("state", "finished");
            completed.addProperty("rev", 3);
            completed.add(
                    "result",
                    object("outcome", "succeeded", "effect", "created", "backupRef", "exact"));
            checkpoint = completed;
            signal.accept(
                    encode(
                            object(
                                    "v",
                                    3,
                                    "type",
                                    "changed",
                                    "instanceId",
                                    UUID.randomUUID(),
                                    "sessionId",
                                    session,
                                    "operationId",
                                    id,
                                    "rev",
                                    3)));
            assertNull(observed.poll(100, TimeUnit.MILLISECONDS));
            signal.accept(
                    encode(
                            object(
                                    "v",
                                    3,
                                    "type",
                                    "changed",
                                    "instanceId",
                                    instance,
                                    "sessionId",
                                    session,
                                    "operationId",
                                    id,
                                    "rev",
                                    3)));
            assertEquals("finished", observed.poll(5, TimeUnit.SECONDS).state());
            assertEquals(1, writes.get());
        }
    }

    @Test
    void originalReceiptMissingOnRestoreDoesNotSubmitAgain() throws Exception {
        UUID id = UUID.randomUUID();
        try (var link =
                client(
                        (inst, type, budget, timeout) -> {
                            if (type.equals("submit")) {
                                writes.incrementAndGet();
                                throw new TimeoutException();
                            }
                            if (type.equals("get"))
                                throw new InteropWire.Rejection("result_unknown");
                            return business(inst, type, budget, timeout);
                        })) {
            link.enter(Files.createDirectory(temp.resolve("world")), false);
            for (int i = 0; i < 100 && link.binding() == null; i++) Thread.sleep(20);
            assertNotNull(link.binding());
            assertEquals(
                    "unconfirmed",
                    string(
                            link.submit(id, "restore", null, object("backupRef", "exact"))
                                    .get(3, TimeUnit.SECONDS),
                            "observation"));
            assertEquals(1, writes.get());
            assertTrue(Files.exists(temp.resolve("state/pending/" + id + ".json")));
        }
    }
    @Test void lostStepAckAndRepeatedHintsDoNotRefreeze() throws Exception {
        UUID op=UUID.randomUUID(),step=UUID.randomUUID();
        AtomicInteger effects=new AtomicInteger(),claims=new AtomicInteger(),acks=new AtomicInteger();
        CountDownLatch released=new CountDownLatch(1);
        effect=s->{effects.incrementAndGet();return InteropSnapshot.Ack.of("frozen");};
        try(var link=client((inst,type,request,timeout)->{
            if(type.equals("step")){
                assertEquals(op,uuid(request,"operationId"));assertEquals(step,uuid(request,"stepId"));
                if(string(request,"verb").equals("claim")){
                    claims.incrementAndGet();
                    return object("stepId",step,"kind","saveAndFreeze","remainingMs",4000);
                }
                assertEquals("frozen",string(request,"outcome"));
                if(acks.incrementAndGet()==1)throw new TimeoutException("lost reply");
                var advanced=checkpoint.deepCopy();advanced.remove("step");advanced.addProperty("rev",3);checkpoint=advanced;
                released.countDown();
                return advanced;
            }
            return business(inst,type,request,timeout);
        })){
            link.enter(Files.createDirectory(temp.resolve("world")),false);
            for(int i=0;i<100&&link.binding()==null;i++)Thread.sleep(20);
            assertNotNull(link.binding());
            checkpoint=object("kind","backup","operationId",op,"targetRef","target","rev",2,"state","running",
                    "step",object("stepId",step,"kind","saveAndFreeze","sessionId",session,"targetRef","target","status","pending","remainingMs",5000));
            String hint=encode(object("v",3,"type","changed","instanceId",instance,"sessionId",session,"operationId",op,"rev",2));
            for(int i=0;i<50 && released.getCount()!=0;i++){signal.accept(hint);Thread.sleep(50);}
            assertTrue(released.await(2,TimeUnit.SECONDS));
            assertEquals(1,claims.get());assertEquals(1,effects.get());
        }
    }
}
