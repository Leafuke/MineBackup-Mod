package com.leafuke.minebackup.api.v1;

import java.util.Objects;
import java.util.Optional;

public record BackupRequest(String callerId, Optional<String> comment) {
    public BackupRequest {
        callerId = requireCallerId(callerId);
        Objects.requireNonNull(comment, "comment");
        comment = comment.map(String::trim).filter(value -> !value.isEmpty());
    }

    public static BackupRequest create(String callerId) {
        return new BackupRequest(callerId, Optional.empty());
    }

    public static BackupRequest create(String callerId, String comment) {
        return new BackupRequest(callerId, Optional.ofNullable(comment));
    }

    static String requireCallerId(String value) {
        Objects.requireNonNull(value, "callerId");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("callerId must not be blank");
        }
        return normalized;
    }
}
