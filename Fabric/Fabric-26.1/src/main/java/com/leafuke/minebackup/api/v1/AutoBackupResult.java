package com.leafuke.minebackup.api.v1;

import java.util.Objects;
import java.util.Optional;

public record AutoBackupResult(
        boolean success,
        AutoBackupState state,
        Optional<OperationFailure> failure) {
    public AutoBackupResult {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(failure, "failure");
        if (success && failure.isPresent()) {
            throw new IllegalArgumentException("Successful automatic backup update must not have a failure");
        }
    }
}
