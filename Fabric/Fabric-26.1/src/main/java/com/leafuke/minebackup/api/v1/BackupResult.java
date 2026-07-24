package com.leafuke.minebackup.api.v1;

import java.util.Objects;
import java.util.Optional;

public record BackupResult(
        Outcome outcome,
        Optional<String> fileName,
        Optional<OperationFailure> failure) {
    public BackupResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(failure, "failure");
        fileName = fileName.map(String::trim).filter(value -> !value.isEmpty());
    }

    public enum Outcome {
        CREATED,
        NO_CHANGES,
        CANCELLED,
        REJECTED,
        FAILED
    }
}
