package com.leafuke.minebackup.runtime;

import com.leafuke.minebackup.api.v2.AutoBackupState;
import com.leafuke.minebackup.api.v2.OperationFailure;

import java.util.Objects;
import java.util.Optional;

public record AutoBackupUpdateResult(
        boolean success,
        AutoBackupState state,
        Optional<OperationFailure> failure) {
    public AutoBackupUpdateResult {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(failure, "failure");
    }
}
