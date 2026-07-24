package com.leafuke.minebackup.api.v1;

import java.util.Objects;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Describes a current-world hot backup.
 *
 * <p>Additional parameters are passed to FolderRewind unchanged apart from
 * lower-casing their keys. MineBackup-owned protocol fields cannot be
 * overridden.</p>
 */
public record BackupRequest(
        String callerId,
        Optional<String> comment,
        Map<String, String> parameters) {
    private static final Set<String> RESERVED_PARAMETERS = Set.of("comment");

    public BackupRequest {
        callerId = requireCallerId(callerId);
        Objects.requireNonNull(comment, "comment");
        comment = comment.map(String::trim).filter(value -> !value.isEmpty());
        parameters = ApiParameterSupport.normalize(parameters, RESERVED_PARAMETERS);
    }

    /**
     * Retains the API v1 constructor that predates additional parameters.
     */
    public BackupRequest(String callerId, Optional<String> comment) {
        this(callerId, comment, Map.of());
    }

    public static BackupRequest create(String callerId) {
        return new BackupRequest(callerId, Optional.empty(), Map.of());
    }

    public static BackupRequest create(String callerId, String comment) {
        return new BackupRequest(callerId, Optional.ofNullable(comment), Map.of());
    }

    /**
     * Returns a new request with one additional FolderRewind parameter.
     * A parameter with the same normalized key replaces its previous value.
     */
    public BackupRequest withParameter(String key, String value) {
        return withParameters(Map.of(key, value));
    }

    /**
     * Returns a new request with the supplied FolderRewind parameters.
     */
    public BackupRequest withParameters(Map<String, String> additions) {
        return new BackupRequest(
                callerId,
                comment,
                ApiParameterSupport.merge(parameters, additions, RESERVED_PARAMETERS));
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
