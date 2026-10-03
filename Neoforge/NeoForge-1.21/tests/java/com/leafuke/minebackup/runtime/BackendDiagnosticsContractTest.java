package com.leafuke.minebackup.runtime;

import com.leafuke.minebackup.api.v2.BackendStatusResult;
import com.leafuke.minebackup.api.v2.OperationFailure;
import com.leafuke.minebackup.api.v2.RestoreCancelRequest;
import com.leafuke.minebackup.knotlink.KnotLinkCommunicationException;
import com.leafuke.minebackup.knotlink.protocol.KnotLinkResponse;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/** Dependency-free API contracts run by the NeoForge check task. */
public final class BackendDiagnosticsContractTest {
    public static void main(String[] args) throws Exception {
        if (!"addon:ui".equals(RestoreCancelRequest.create(" Addon:UI ", UUID.randomUUID()).callerId()))
            throw new AssertionError("Caller normalization");
        var origin = new AtomicReference<Object>(new Object());
        for (boolean data : new boolean[] {true, false}) {
            String payload = "enabled=True;initialized=False;active_tasks=2";
            var queries = new BackendQueries(request -> CompletableFuture.completedFuture(new KnotLinkResponse(
                    KnotLinkResponse.Status.OK, data ? null : payload, data ? payload : null, Map.of("status", "ok"))),
                    origin::get, () -> BackendStatusResult.ChannelState.UNREACHABLE);
            var result = queries.status().toCompletableFuture().get();
            if (result.outcome() != BackendStatusResult.Outcome.SUCCESS || !result.enabled().orElseThrow()
                    || result.initialized().orElseThrow() || result.activeTasks().orElseThrow() != 2
                    || result.activeAutomaticBackups().isPresent()
                    || result.signalChannel() != BackendStatusResult.ChannelState.UNREACHABLE)
                throw new AssertionError("Independent backend status observations");
        }
        var offline = new BackendQueries(request -> CompletableFuture.failedFuture(new KnotLinkCommunicationException(
                OperationFailure.Code.BACKEND_OFFLINE, "offline", null)), origin::get,
                () -> BackendStatusResult.ChannelState.UNKNOWN).status().toCompletableFuture().get();
        if (offline.responder() != BackendStatusResult.ResponderState.OFFLINE
                || offline.failure().orElseThrow().code() != OperationFailure.Code.BACKEND_OFFLINE)
            throw new AssertionError("Responder offline classification");
        var pending = new CompletableFuture<KnotLinkResponse>();
        var late = new BackendQueries(request -> pending, origin::get,
                () -> BackendStatusResult.ChannelState.CONNECTED).status();
        origin.set(null);
        pending.complete(new KnotLinkResponse(KnotLinkResponse.Status.OK, null, "enabled=True", Map.of("status", "ok")));
        if (late.toCompletableFuture().get().outcome() != BackendStatusResult.Outcome.UNAVAILABLE)
            throw new AssertionError("World lifecycle guard");
        System.out.println("Backend diagnostics contracts passed");
    }
}
