package com.leafuke.minebackup.runtime;

import com.leafuke.minebackup.api.v2.*;
import com.leafuke.minebackup.knotlink.KnotLinkCommunicationException;
import com.leafuke.minebackup.knotlink.protocol.KnotLinkResponse;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BackendQueriesTest {
    private static KnotLinkResponse ok(String data, String message) {
        return new KnotLinkResponse(KnotLinkResponse.Status.OK, message, data, Map.of("status", "ok"));
    }

    @Test void supportsBothBackendsAndKeepsSignalChannelIndependent() throws Exception {
        for (boolean data : new boolean[] {true, false}) {
            String payload = "enabled=True;initialized=false;active_tasks=2;active_auto_backups=3";
            var queries = new BackendQueries(request -> {
                assertEquals("GET_STATUS", request.commandName());
                assertFalse(request.serialize().contains("current_save"));
                assertFalse(request.serialize().contains("request_id"));
                return CompletableFuture.completedFuture(ok(data ? payload : null, data ? null : payload));
            }, () -> this, () -> BackendStatusResult.ChannelState.UNREACHABLE);
            var result = queries.status().toCompletableFuture().get();
            assertEquals(BackendStatusResult.Outcome.SUCCESS, result.outcome());
            assertEquals(BackendStatusResult.ChannelState.CONNECTED, result.queryChannel());
            assertEquals(BackendStatusResult.ChannelState.UNREACHABLE, result.signalChannel());
            assertEquals(BackendStatusResult.ResponderState.ONLINE, result.responder());
            assertTrue(result.enabled().orElseThrow());
            assertFalse(result.initialized().orElseThrow());
            assertEquals(2, result.activeTasks().orElseThrow());
            assertEquals(3, result.activeAutomaticBackups().orElseThrow());
        }
    }

    @Test void missingFieldsRemainUnknownAndMalformedFieldsFail() throws Exception {
        var result = BackendStatusParser.parse(ok("enabled=True;active_tasks=0", null), BackendStatusResult.ChannelState.UNKNOWN);
        assertTrue(result.initialized().isEmpty());
        assertTrue(result.activeAutomaticBackups().isEmpty());
        for (String invalid : new String[] {"enabled=maybe", "active_tasks=-1", "active_tasks=2147483648", "initialized=1", "active_tasks=x"}) {
            var queries = new BackendQueries(request -> CompletableFuture.completedFuture(ok(invalid, null)),
                    () -> this, () -> BackendStatusResult.ChannelState.CONNECTED);
            assertEquals(OperationFailure.Code.PROTOCOL_ERROR,
                    queries.status().toCompletableFuture().get().failure().orElseThrow().code());
        }
    }

    @Test void observesConnectionFailuresWithoutInventingBackendState() throws Exception {
        for (var code : new OperationFailure.Code[] {OperationFailure.Code.KNOTLINK_UNREACHABLE,
                OperationFailure.Code.BACKEND_OFFLINE, OperationFailure.Code.RESPONSE_TIMEOUT,
                OperationFailure.Code.CONNECTION_CLOSED, OperationFailure.Code.CLIENT_CLOSED,
                OperationFailure.Code.QUERY_QUEUE_FULL, OperationFailure.Code.PROTOCOL_ERROR}) {
            var queries = new BackendQueries(request -> CompletableFuture.failedFuture(
                    new KnotLinkCommunicationException(code, "detail", null)),
                    () -> this, () -> BackendStatusResult.ChannelState.UNKNOWN);
            var result = queries.status().toCompletableFuture().get();
            assertEquals(code, result.failure().orElseThrow().code());
            assertEquals(code == OperationFailure.Code.BACKEND_OFFLINE
                    ? BackendStatusResult.ResponderState.OFFLINE : BackendStatusResult.ResponderState.UNKNOWN, result.responder());
            if (code == OperationFailure.Code.BACKEND_OFFLINE || code == OperationFailure.Code.RESPONSE_TIMEOUT)
                assertEquals(BackendStatusResult.ChannelState.CONNECTED, result.queryChannel());
        }
    }

    @Test void rejectsLateResultsFromClosedOrReplacedWorld() throws Exception {
        var origin = new AtomicReference<Object>(new Object());
        var pending = new CompletableFuture<KnotLinkResponse>();
        var queries = new BackendQueries(request -> pending, origin::get, () -> BackendStatusResult.ChannelState.CONNECTED);
        var status = queries.status();
        var capabilities = queries.capabilities();
        origin.set(new Object());
        pending.complete(ok("enabled=true", null));
        assertEquals(BackendStatusResult.Outcome.UNAVAILABLE, status.toCompletableFuture().get().outcome());
        assertEquals(BackendCapabilitiesResult.Outcome.UNAVAILABLE, capabilities.toCompletableFuture().get().outcome());
        origin.set(null);
        assertEquals(BackendStatusResult.Outcome.UNAVAILABLE, queries.status().toCompletableFuture().get().outcome());
    }

    @Test void rejectsInvalidCapabilitiesAndKeepsDeclaredAliases() {
        String args = "{\"args\":{\"cmd\":{\"type\":\"static\",\"value\":\"RESTORE\"}}}";
        var result = BackendCapabilitiesParser.parse("{\"specVersion\":\"1.0\",\"manifestVersion\":\"x\",\"openSocket\":{\"a\":"+args+",\"b\":"+args+"}}");
        assertEquals(1, result.commands().size());
        assertThrows(RuntimeException.class, () -> BackendCapabilitiesParser.parse("x".repeat(1_048_577)));
        String conflict = args.replace("\"value\":\"RESTORE\"", "\"value\":\"RESTORE\",\"defaultVal\":\"other\"");
        assertThrows(RuntimeException.class, () -> BackendCapabilitiesParser.parse(
                "{\"specVersion\":\"1.0\",\"manifestVersion\":\"x\",\"openSocket\":{\"a\":"+args+",\"b\":"+conflict+"}}"));
    }
}
