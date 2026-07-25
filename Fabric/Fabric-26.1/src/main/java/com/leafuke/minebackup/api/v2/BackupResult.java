package com.leafuke.minebackup.api.v2;

import java.util.Objects;
import java.util.Optional;

public record BackupResult(
        Outcome outcome,
        Optional<BackupId> backupId,
        Optional<OperationFailure> failure) {
    public BackupResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(backupId, "backupId");
        Objects.requireNonNull(failure, "failure");
    }

    public enum Outcome {
        CREATED,
        NO_CHANGES,
        CANCELLED,
        REJECTED,
        FAILED
    }
}
