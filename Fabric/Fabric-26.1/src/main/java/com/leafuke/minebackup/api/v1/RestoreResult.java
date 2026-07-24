package com.leafuke.minebackup.api.v1;

import java.util.Objects;
import java.util.Optional;

public record RestoreResult(
        Outcome outcome,
        Optional<String> fileName,
        Optional<OperationFailure> failure) {
    public RestoreResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(failure, "failure");
        fileName = fileName.map(String::trim).filter(value -> !value.isEmpty());
    }

    public enum Outcome {
        RESTORED,
        RESTORED_REJOIN_FAILED,
        CANCELLED,
        REJECTED,
        FAILED
    }
}
