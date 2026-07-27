package com.leafuke.minebackup.api.v2;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Legacy FolderRewind returns names only; successful entry order is undefined. */
public record BackupCatalogResult(
        Outcome outcome,
        List<BackupEntry> entries,
        Optional<OperationFailure> failure) {
    public BackupCatalogResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(failure, "failure");
        entries = List.copyOf(entries);
    }

    public static BackupCatalogResult success(List<BackupEntry> entries) {
        return new BackupCatalogResult(Outcome.SUCCESS, entries, Optional.empty());
    }

    public static BackupCatalogResult failed(Outcome outcome, OperationFailure failure) {
        return new BackupCatalogResult(outcome, List.of(), Optional.of(failure));
    }

    public enum Outcome {
        SUCCESS,
        BUSY,
        REJECTED,
        FAILED
    }
}
