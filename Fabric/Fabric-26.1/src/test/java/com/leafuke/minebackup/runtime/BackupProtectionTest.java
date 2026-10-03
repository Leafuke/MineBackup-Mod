package com.leafuke.minebackup.runtime;
import com.leafuke.minebackup.api.v2.*;
import com.leafuke.minebackup.knotlink.protocol.*;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(5)
class BackupProtectionTest {
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final List<KnotLinkRequest> sent = new ArrayList<>();
    private Function<KnotLinkRequest, CompletableFuture<KnotLinkResponse>> reply = r -> CompletableFuture.completedFuture(ok(Map.of()));
    private CurrentWorldOperationCoordinator coordinator;
    @BeforeEach void init() {
        coordinator = new CurrentWorldOperationCoordinator(r -> { sent.add(r); return reply.apply(r); }, scheduler,
            () -> true, () -> false, () -> 5, new CurrentWorldOperationCoordinator.CountdownListener() {
                public void onStarted(InternalRestoreHandle h, int s) {} public void onTick(InternalRestoreHandle h, int s) {}
                public void onConfirmed(InternalRestoreHandle h) {} public void onCancelled(InternalRestoreHandle h) {}
                public void onSubmitted(InternalRestoreHandle h) {}
            });
        reply = r -> CompletableFuture.completedFuture("GET_CAPABILITIES".equals(r.commandName()) ? capabilities(true) : receipt(r));
    }
    @AfterEach void close() { coordinator.close(); scheduler.shutdownNow(); }
    private static KnotLinkResponse ok(Map<String,String> fields) { return new KnotLinkResponse(KnotLinkResponse.Status.OK, "ok", null, fields); }
    private static KnotLinkResponse capabilities(boolean supported) {
        var commands = new com.google.gson.JsonObject();
        if (supported) for (String cmd : List.of("BACKUP", "GET_IMPORTANCE", "MARK_IMPORTANT")) {
            var args = new com.google.gson.JsonObject(); var name = new com.google.gson.JsonObject();
            name.addProperty("type", "static"); name.addProperty("value", cmd); args.add("cmd", name);
            var protect = new com.google.gson.JsonObject(); protect.addProperty("type", "input"); args.add("protect", protect);
            var function = new com.google.gson.JsonObject(); function.add("args", args); commands.add(cmd, function);
        }
        var manifest = new com.google.gson.JsonObject(); manifest.addProperty("specVersion", "1.0");
        manifest.addProperty("manifestVersion", "test"); manifest.add("openSocket", commands);
        return ok(Map.of("func_list", manifest.toString()));
    }
    private static KnotLinkResponse receipt(KnotLinkRequest request) {
        try {
            var fields = new HashMap<>(KnotLinkCodec.parse(request.serialize()));
            fields.putIfAbsent("important", "false"); return ok(fields);
        } catch (Exception e) { throw new AssertionError(e); }
    }
    private void signal(BackupOperationHandle h, String result, String file, String important) {
        coordinator.handleSignal(Map.of("event","command_completed","command","BACKUP","from",KnotLinkRequest.CALLER_ID,
            "request_id",h.id().toString(),"result",result,"file",file,"important",important));
    }
    @Test void protectedCreationWaitsForFinalReceiptAndReturnsReuse() {
        var h = coordinator.startBackup(BackupRequest.create("test"), true);
        coordinator.handleSignal(Map.of("event","backup_success","request_id",h.id().toString(),"file","early.7z"));
        assertFalse(h.completion().toCompletableFuture().isDone());
        coordinator.handleSignal(Map.of("event","command_completed","request_id",UUID.randomUUID().toString(),"file","wrong.7z"));
        assertFalse(h.completion().toCompletableFuture().isDone());
        signal(h,"reused","original.7z","true");
        var result = h.protectedView().completion().toCompletableFuture().join();
        assertEquals(ProtectedBackupResult.Outcome.REUSED,result.outcome());
        assertTrue(result.important()); assertEquals("original.7z", result.backupId().orElseThrow().value());
        assertTrue(sent.get(1).serialize().contains("protect=true"));
    }
    @Test void missingProtectionAndFilenameNeverBecomeSuccess() {
        for (var values : List.of(List.of("created","a.7z","false"), List.of("created","","true"),List.of("warning","a.7z","true"))) {
            var h = coordinator.startBackup(BackupRequest.create("test"), true);
            signal(h,values.get(0),values.get(1),values.get(2));
            assertEquals(ProtectedBackupResult.Outcome.FAILED,h.protectedView().completion().toCompletableFuture().join().outcome());
        }
    }
    @Test void unsupportedBackendDoesNotSubmitBackup() {
        reply = r -> CompletableFuture.completedFuture(capabilities(false));
        var h = coordinator.startBackup(BackupRequest.create("test"), true);
        assertEquals(ProtectedBackupResult.Outcome.UNSUPPORTED,h.protectedView().completion().toCompletableFuture().join().outcome());
        assertEquals(1,sent.size());
    }
    @Test void worldClosureDuringDiscoveryPreventsSubmissionAndReleasesGate() {
        var pending = new CompletableFuture<KnotLinkResponse>(); reply = r -> pending;
        var h = coordinator.startBackup(BackupRequest.create("test"), true);
        assertEquals(BackupResult.Outcome.REJECTED,coordinator.backupCurrent(BackupRequest.create("busy")).completion().toCompletableFuture().join().outcome());
        coordinator.serverStopping(false); pending.complete(capabilities(true));
        assertEquals(ProtectedBackupResult.Outcome.FAILED,h.protectedView().completion().toCompletableFuture().join().outcome());
        assertEquals(1,sent.size()); assertFalse(coordinator.isBusy());
    }
    @Test void markerAndQueryReturnKnownStateOnlyOnExactReceipt() {
        var id=BackupId.of("中文.7z");
        var set=coordinator.protection(BackupProtectionRequest.set("test",id,true),true).completion().toCompletableFuture().join();
        assertEquals(Optional.of(true),set.important());
        var query=coordinator.protection(BackupProtectionRequest.query("test",id),false).completion().toCompletableFuture().join();
        assertEquals(Optional.of(false),query.important());
    }
    @Test void wrongCorrelationMissingTargetAndTimeoutAreNotUnprotected() {
        for (int mode=0;mode<3;mode++) {
            final int kind=mode;
            reply=r -> {
                if (r.commandName().equals("GET_CAPABILITIES")) return CompletableFuture.completedFuture(capabilities(true));
                if (kind==2) return CompletableFuture.failedFuture(new TimeoutException("timeout"));
                if (kind==1) return CompletableFuture.completedFuture(new KnotLinkResponse(KnotLinkResponse.Status.ERROR,"Backup entry not found",null,Map.of()));
                return CompletableFuture.completedFuture(ok(Map.of("request_id",UUID.randomUUID().toString(),"file","a.7z","important","false")));
            };
            var result=coordinator.protection(BackupProtectionRequest.query("test",BackupId.of("a.7z")),false).completion().toCompletableFuture().join();
            assertEquals(BackupProtectionResult.Outcome.FAILED,result.outcome()); assertTrue(result.important().isEmpty());
            assertFalse(coordinator.isBusy());
        }
    }
    @Test void protectedCancellationRemainsDistinctFromFailure() {
        var h = coordinator.startBackup(BackupRequest.create("test"), true);
        coordinator.handleSignal(Map.of("event","command_failed","command","BACKUP","from",KnotLinkRequest.CALLER_ID,
            "request_id",h.id().toString(),"reason","canceled"));
        assertEquals(ProtectedBackupResult.Outcome.CANCELLED,h.protectedView().completion().toCompletableFuture().join().outcome());
    }
    @Test void synchronousTransportFailureCompletesAndReleasesGate() {
        reply = r -> { throw new IllegalStateException("transport closed"); };
        var h = coordinator.startBackup(BackupRequest.create("test"), true);
        assertEquals(ProtectedBackupResult.Outcome.FAILED,h.protectedView().completion().toCompletableFuture().join().outcome());
        assertFalse(coordinator.isBusy());
        var query = coordinator.protection(BackupProtectionRequest.query("test",BackupId.of("a.7z")),false);
        assertEquals(BackupProtectionResult.Outcome.FAILED,query.completion().toCompletableFuture().join().outcome());
        assertFalse(coordinator.isBusy());
    }
    @Test void rawProtectOverrideIsRejected() {
        assertThrows(IllegalArgumentException.class,()->coordinator.startBackup(BackupRequest.create("test").withParameter("protect","false"),true));
    }
}
