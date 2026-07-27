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
        OperationPresentation presentation) {
    private static final Set<String> RESERVED_PARAMETERS = Set.of("file", "comment");

    public RestoreRequest {
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
                presentation);
    }

    public RestoreRequest withPresentation(OperationPresentation value) {
        return new RestoreRequest(callerId, backupId, comment, executionPolicy, parameters, value);
    }

    public RestoreRequest withComment(String value) {
        return new RestoreRequest(
                callerId,
                backupId,
                Optional.ofNullable(value),
                executionPolicy,
                parameters,
                presentation);
    }
}
