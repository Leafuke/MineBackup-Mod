package com.leafuke.minebackup.dedicated;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

final class SidecarSignalTracker {
    private final UUID requestId;
    private final String worldId;
    private Outcome terminal;
    private boolean rejoinRequested;

    SidecarSignalTracker(UUID requestId, String worldId) {
        this.requestId = requestId;
        this.worldId = worldId;
    }

    synchronized void accept(Map<String, String> fields) {
        String signalRequest = fields.get("request_id");
        if (signalRequest != null && !signalRequest.equals(requestId.toString())) {
            return;
        }
        String signalWorld = fields.get("world");
        if (signalWorld != null && !signalWorld.equals(worldId)) {
            return;
        }
        String event = fields.get("event");
        if ("restore_finished".equals(event) && terminal == null) {
            terminal = "success".equalsIgnoreCase(fields.get("status"))
                    ? Outcome.SUCCESS
                    : Outcome.FAILURE;
        } else if ("restore_cancelled".equals(event) && terminal == null) {
            terminal = Outcome.CANCELLED;
        } else if ("rejoin_world".equals(event)) {
            rejoinRequested = true;
        }
    }

    synchronized Optional<Outcome> terminal() {
        return Optional.ofNullable(terminal);
    }

    synchronized boolean rejoinRequested() {
        return rejoinRequested;
    }

    enum Outcome {
        SUCCESS,
        FAILURE,
        CANCELLED
    }
}
