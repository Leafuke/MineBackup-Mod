package com.leafuke.minebackup.api.v2;

import java.util.Objects;
import java.util.Optional;

public record RestoreResult(
        Outcome outcome,
        Optional<BackupId> backupId,
        Optional<OperationFailure> failure) {
    public RestoreResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(backupId, "backupId");
        Objects.requireNonNull(failure, "failure");
    }

    public enum Outcome {
        RESTORED,
        RESTORED_REJOIN_FAILED,
        RESTART_HANDOFF_ACCEPTED,
        CANCELLED,
        REJECTED,
        FAILED
    }
}
