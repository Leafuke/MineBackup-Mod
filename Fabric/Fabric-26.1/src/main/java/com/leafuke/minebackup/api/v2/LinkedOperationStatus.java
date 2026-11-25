package com.leafuke.minebackup.api.v2;

import java.util.Optional;
import java.util.UUID;

/** A read-only observation. A missing snapshot leaves the backend outcome unconfirmed. */
public record LinkedOperationStatus(
        UUID domainId,
        UUID operationId,
        Optional<Snapshot> snapshot,
        Optional<OperationFailure> failure) {
    public record Snapshot(
            String kind,
            String targetRef,
            long revision,
            String state,
            Optional<String> outcome,
            Optional<String> disk,
            boolean recoveryPending,
            Optional<String> reopen,
            Optional<String> followUp) {}
}
