package com.leafuke.minebackup.dedicated;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import com.leafuke.minebackup.knotlink.protocol.KnotLinkCodec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SidecarSignalTrackerTest {
    @Test
    void ignoresWrongRequestWorldAndDuplicateTerminalSignals() {
        UUID request = UUID.randomUUID();
        SidecarSignalTracker tracker = new SidecarSignalTracker(request, "world");
        tracker.accept(Map.of(
                "event", "restore_finished",
                "request_id", UUID.randomUUID().toString(),
                "world", "world",
                "status", "success"));
        tracker.accept(Map.of(
                "event", "restore_finished",
                "request_id", request.toString(),
                "world", "other",
                "status", "success"));
        assertTrue(tracker.terminal().isEmpty());

        tracker.accept(Map.of(
                "event", "restore_finished",
                "request_id", request.toString(),
                "world", "world",
                "status", "failure"));
        tracker.accept(Map.of(
                "event", "restore_finished",
                "request_id", request.toString(),
                "world", "world",
                "status", "success"));
        assertEquals(
                SidecarSignalTracker.Outcome.FAILURE,
                tracker.terminal().orElseThrow());
    }

    @Test
    void buffersRejoinBeforeTerminal() {
        SidecarSignalTracker tracker = new SidecarSignalTracker(UUID.randomUUID(), "world");
        tracker.accept(Map.of("event", "rejoin_world", "world", "world"));
        assertTrue(tracker.rejoinRequested());
        assertFalse(tracker.terminal().isPresent());
    }

    @Test
    void rejoinResultUsesExistingProtocolFields() throws Exception {
        UUID request = UUID.randomUUID();
        Map<String, String> fields = KnotLinkCodec.parse(
                DedicatedRestoreSidecar.rejoinRequest(request, true, "").serialize());
        assertEquals("REJOIN_RESULT", fields.get("cmd"));
        assertEquals("success", fields.get("result"));
        assertFalse(fields.containsKey("status"));
        assertEquals(request.toString(), fields.get("request_id"));
    }
}
