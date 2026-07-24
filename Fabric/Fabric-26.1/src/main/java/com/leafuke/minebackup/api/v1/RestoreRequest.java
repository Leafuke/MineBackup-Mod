package com.leafuke.minebackup.api.v1;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Describes a current-world hot restore.
 *
 * <p>Additional parameters are passed to FolderRewind unchanged apart from
 * lower-casing their keys. MineBackup-owned protocol fields cannot be
 * overridden.</p>
 */
public record RestoreRequest(
        String callerId,
        Optional<String> fileName,
        RestoreExecutionPolicy executionPolicy,
        Map<String, String> parameters) {
    private static final Set<String> RESERVED_PARAMETERS = Set.of("file");

    public RestoreRequest {
        callerId = BackupRequest.requireCallerId(callerId);
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(executionPolicy, "executionPolicy");
        fileName = fileName.map(String::trim).filter(value -> !value.isEmpty());
        parameters = ApiParameterSupport.normalize(parameters, RESERVED_PARAMETERS);
    }

    /**
     * Retains the API v1 constructor that predates additional parameters.
     */
    public RestoreRequest(
            String callerId,
            Optional<String> fileName,
            RestoreExecutionPolicy executionPolicy) {
        this(callerId, fileName, executionPolicy, Map.of());
    }

    public static RestoreRequest latest(String callerId) {
        return new RestoreRequest(
                callerId,
                Optional.empty(),
                RestoreExecutionPolicy.CONFIGURED_COUNTDOWN,
                Map.of());
    }

    public static RestoreRequest file(String callerId, String fileName) {
        return new RestoreRequest(
                callerId,
                Optional.ofNullable(fileName),
                RestoreExecutionPolicy.CONFIGURED_COUNTDOWN,
                Map.of());
    }

    public RestoreRequest immediate() {
        return new RestoreRequest(
                callerId,
                fileName,
                RestoreExecutionPolicy.IMMEDIATE,
                parameters);
    }

    /**
     * Returns a new request with one additional FolderRewind parameter.
     * A parameter with the same normalized key replaces its previous value.
     */
    public RestoreRequest withParameter(String key, String value) {
        return withParameters(Map.of(key, value));
    }

    /**
     * Returns a new request with the supplied FolderRewind parameters.
     */
    public RestoreRequest withParameters(Map<String, String> additions) {
        return new RestoreRequest(
                callerId,
                fileName,
                executionPolicy,
                ApiParameterSupport.merge(parameters, additions, RESERVED_PARAMETERS));
    }
}
