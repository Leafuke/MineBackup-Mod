package com.leafuke.minebackup.api.v2;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record RestoreRequest(
        String callerId,
        Optional<BackupId> backupId,
        Optional<String> comment,
        RestoreExecutionPolicy executionPolicy,
        Map<String, String> parameters,
        OperationPresentation presentation, java.util.OptionalInt countdownSeconds) {
    private static final Set<String> RESERVED_PARAMETERS = Set.of("file", "comment");

    public RestoreRequest(String callerId, Optional<BackupId> backupId, Optional<String> comment,
            RestoreExecutionPolicy executionPolicy, Map<String, String> parameters, OperationPresentation presentation) {
        this(callerId, backupId, comment, executionPolicy, parameters, presentation, java.util.OptionalInt.empty());
    }

    public RestoreRequest {
        Objects.requireNonNull(countdownSeconds, "countdownSeconds");
        if (countdownSeconds.isPresent() && (countdownSeconds.getAsInt() < 0 || countdownSeconds.getAsInt() > 300))
            throw new IllegalArgumentException("Countdown must be between 0 and 300 seconds");
        if (executionPolicy == RestoreExecutionPolicy.IMMEDIATE) countdownSeconds = java.util.OptionalInt.empty();
        callerId = CallerId.normalize(callerId);
        Objects.requireNonNull(backupId, "backupId");
        Objects.requireNonNull(comment, "comment");
        comment = comment.map(String::trim).filter(value -> !value.isEmpty());
        Objects.requireNonNull(executionPolicy, "executionPolicy");
        parameters = ApiParameterSupport.normalize(parameters, RESERVED_PARAMETERS);
        Objects.requireNonNull(presentation, "presentation");
    }

    public static RestoreRequest latest(String callerId) {
        return new RestoreRequest(
                callerId,
                Optional.empty(),
                Optional.empty(),
                RestoreExecutionPolicy.CONFIGURED_COUNTDOWN,
                Map.of(),
                OperationPresentation.defaults());
    }

    public static RestoreRequest backup(String callerId, BackupId backupId) {
        return new RestoreRequest(
                callerId,
                Optional.of(Objects.requireNonNull(backupId, "backupId")),
                Optional.empty(),
                RestoreExecutionPolicy.CONFIGURED_COUNTDOWN,
                Map.of(),
                OperationPresentation.defaults());
    }

    public static RestoreRequest file(String callerId, String fileName) {
        return backup(callerId, BackupId.of(fileName));
    }

    /** Overrides this operation only; never changes configuration or backend parameters. */
    public RestoreRequest withCountdownSeconds(int seconds) {
        return new RestoreRequest(callerId, backupId, comment, RestoreExecutionPolicy.CONFIGURED_COUNTDOWN,
                parameters, presentation, java.util.OptionalInt.of(seconds));
    }

    public RestoreRequest immediate() {
        return new RestoreRequest(
                callerId,
                backupId,
                comment,
                RestoreExecutionPolicy.IMMEDIATE,
                parameters,
                presentation);
    }

    public RestoreRequest withParameter(String key, String value) {
        return withParameters(Map.of(key, value));
    }

    public RestoreRequest withParameters(Map<String, String> additions) {
        return new RestoreRequest(
                callerId,
                backupId,
                comment,
                executionPolicy,
                ApiParameterSupport.merge(parameters, additions, RESERVED_PARAMETERS),
                presentation, countdownSeconds);
    }

    public RestoreRequest withPresentation(OperationPresentation value) {
        return new RestoreRequest(callerId, backupId, comment, executionPolicy, parameters, value, countdownSeconds);
    }

    public RestoreRequest withComment(String value) {
        return new RestoreRequest(
                callerId,
                backupId,
                Optional.ofNullable(value),
                executionPolicy,
                parameters,
                presentation, countdownSeconds);
    }
}
