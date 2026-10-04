package com.leafuke.minebackup.runtime;

import com.leafuke.minebackup.api.v2.*;
import com.leafuke.minebackup.knotlink.protocol.KnotLinkResponse;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CapabilityCacheTest {
    private static KnotLinkResponse manifest() {
        return new KnotLinkResponse(KnotLinkResponse.Status.OK, "ok", null, Map.of("func_list",
            "{\"specVersion\":\"1.0\",\"manifestVersion\":\"same\",\"openSocket\":{\"restore\":{\"args\":{\"cmd\":{\"type\":\"static\",\"value\":\"RESTORE\"}}}}}"));
    }

    @Test void coalescesConcurrentDiscoveryAndIsolatesCallerCancellation() {
        var pending = new CompletableFuture<KnotLinkResponse>();
        var calls = new AtomicInteger();
        var queries = new BackendQueries(request -> { calls.incrementAndGet(); return pending; },
            () -> this, () -> BackendStatusResult.ChannelState.CONNECTED);
        var first = queries.capabilities().toCompletableFuture();
        var second = queries.capabilities(true).toCompletableFuture();
        first.cancel(false);
        pending.complete(manifest());
        assertEquals(BackendCapabilitiesResult.Outcome.SUCCESS, second.join().outcome());
        assertEquals(second.join(), queries.capabilities().toCompletableFuture().join());
        assertEquals(1, calls.get());
    }

    @Test void refreshReconnectAndWorldChangeRejectStaleResponses() {
        var replies = new ArrayList<CompletableFuture<KnotLinkResponse>>();
        var world = new AtomicReference<Object>(new Object());
        var epoch = new AtomicLong();
        var queries = new BackendQueries(request -> {
            var response = new CompletableFuture<KnotLinkResponse>(); replies.add(response); return response;
        }, world::get, () -> BackendStatusResult.ChannelState.CONNECTED, epoch::get);
        var old = queries.capabilities();
        epoch.incrementAndGet();
        var current = queries.capabilities();
        replies.get(0).complete(manifest());
        replies.get(1).complete(manifest());
        assertEquals(BackendCapabilitiesResult.Outcome.UNAVAILABLE, old.toCompletableFuture().join().outcome());
        long firstGeneration = current.toCompletableFuture().join().generation();
        var refreshed = queries.capabilities(true);
        replies.get(2).complete(manifest());
        assertNotEquals(firstGeneration, refreshed.toCompletableFuture().join().generation());
        world.set(new Object());
        var nextWorld = queries.capabilities();
        world.set(null);
        replies.get(3).complete(manifest());
        assertEquals(BackendCapabilitiesResult.Outcome.UNAVAILABLE, nextWorld.toCompletableFuture().join().outcome());
    }

    @Test void failureIsNotPermanentAndExplicitRefreshRecovers() {
        var calls = new AtomicInteger();
        var queries = new BackendQueries(request -> calls.incrementAndGet() == 1
            ? CompletableFuture.failedFuture(new TimeoutException("test")) : CompletableFuture.completedFuture(manifest()),
            () -> this, () -> BackendStatusResult.ChannelState.CONNECTED);
        assertEquals(BackendCapabilitiesResult.Outcome.FAILED, queries.capabilities().toCompletableFuture().join().outcome());
        queries.capabilities().toCompletableFuture().join();
        assertEquals(1, calls.get());
        assertEquals(BackendCapabilitiesResult.Outcome.SUCCESS, queries.capabilities(true).toCompletableFuture().join().outcome());
        assertEquals(2, calls.get());
    }
}
