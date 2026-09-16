package com.leafuke.minebackup.api.v2;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record DedicatedRestoreStatus(
        UUID requestId,
        State state,
        Instant updatedAt,
        Optional<String> detail) {
    public DedicatedRestoreStatus {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(detail, "detail");
        detail = detail.map(String::trim).filter(value -> !value.isEmpty());
    }

    public enum State {
        FAILED,
        CANCELLED,
        RESTART_STARTED,
        RESTART_FAILED,
        UNCERTAIN
    }
}
