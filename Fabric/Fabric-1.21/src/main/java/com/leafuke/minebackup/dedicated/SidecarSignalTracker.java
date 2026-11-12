package com.leafuke.minebackup.dedicated;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

final class SidecarSignalTracker {
    private final String requestId;
    private final String from;
    private final String worldId;
    private Outcome terminal;
    private boolean rejoinRequested;
    private boolean releaseRequested;

    SidecarSignalTracker(UUID requestId, String worldId) {
        this(requestId.toString(), null, worldId);
    }

    SidecarSignalTracker(String requestId, String from, String worldId) {
        this.requestId = requestId;
        this.from = from;
        this.worldId = worldId;
    }

    synchronized void accept(Map<String, String> fields) {
        String signalRequest = fields.get("request_id");
        if (signalRequest != null && !signalRequest.equals(requestId)) {
            return;
        }
        String signalWorld = fields.get("world");
        if (signalRequest == null && signalWorld == null) return;
        if (from != null && fields.containsKey("from") && !from.equals(fields.get("from"))) return;
        if (signalWorld != null && !signalWorld.equals(worldId)) {
            return;
        }
        String event = fields.get("event");
        if ("restore_finished".equals(event) && terminal == null) {
            if (!releaseRequested) return;
            String status = fields.getOrDefault("status", "");
            if (!("success".equalsIgnoreCase(status) || "failure".equalsIgnoreCase(status)
                    || "failed".equalsIgnoreCase(status))) return;
            terminal = "success".equalsIgnoreCase(fields.get("status"))
                    ? Outcome.SUCCESS
                    : Outcome.FAILURE;
        } else if ("restore_cancelled".equals(event) && terminal == null) {
            terminal = Outcome.CANCELLED;
        } else if ("rejoin_world".equals(event) && releaseRequested) {
            rejoinRequested = true;
        }
    }

    synchronized void releaseRequested() { releaseRequested = true; }

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
