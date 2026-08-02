package com.leafuke.minebackup.api.v2;

import java.util.Objects;

/** A backend backup name that is safe to use as one file-system path segment. */
public record BackupId(String value) {
    public BackupId {
        Objects.requireNonNull(value, "value");
        value = value.trim();
        if (value.isEmpty()
                || ".".equals(value)
                || "..".equals(value)
                || value.indexOf('/') >= 0
                || value.indexOf('\\') >= 0
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("BackupId must be a safe single file name");
        }
    }

    public static BackupId of(String value) {
        return new BackupId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
