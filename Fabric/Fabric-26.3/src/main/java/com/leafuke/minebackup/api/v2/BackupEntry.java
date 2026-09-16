package com.leafuke.minebackup.api.v2;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

public record BackupEntry(
        BackupId backupId,
        Optional<Instant> createdAt,
        OptionalLong sizeBytes,
        Optional<String> comment) {
    public BackupEntry {
        Objects.requireNonNull(backupId, "backupId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(sizeBytes, "sizeBytes");
        Objects.requireNonNull(comment, "comment");
        if (sizeBytes.isPresent() && sizeBytes.getAsLong() < 0L) {
            throw new IllegalArgumentException("sizeBytes must not be negative");
        }
        comment = comment.map(String::trim).filter(value -> !value.isEmpty());
    }

    public static BackupEntry legacy(BackupId backupId) {
        return new BackupEntry(
                backupId, Optional.empty(), OptionalLong.empty(), Optional.empty());
    }
}
