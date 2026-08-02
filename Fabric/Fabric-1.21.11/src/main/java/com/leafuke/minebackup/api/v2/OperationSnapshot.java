package com.leafuke.minebackup.api.v2;

import java.util.Objects;
import java.util.UUID;

public record OperationSnapshot(
        UUID requestId,
        String callerId,
        OperationType type,
        OperationPhase phase) {
    public OperationSnapshot {
        Objects.requireNonNull(requestId, "requestId");
        callerId = CallerId.normalize(callerId);
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(phase, "phase");
    }
}
