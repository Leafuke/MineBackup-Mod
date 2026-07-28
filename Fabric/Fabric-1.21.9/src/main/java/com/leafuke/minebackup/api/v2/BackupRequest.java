package com.leafuke.minebackup.api.v2;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record BackupRequest(
        String callerId,
        Optional<String> comment,
        Map<String, String> parameters,
        OperationPresentation presentation) {
    private static final Set<String> RESERVED_PARAMETERS = Set.of("comment");

    public BackupRequest {
        callerId = CallerId.normalize(callerId);
        Objects.requireNonNull(comment, "comment");
        comment = comment.map(String::trim).filter(value -> !value.isEmpty());
        parameters = ApiParameterSupport.normalize(parameters, RESERVED_PARAMETERS);
        Objects.requireNonNull(presentation, "presentation");
    }

    public static BackupRequest create(String callerId) {
        return new BackupRequest(callerId, Optional.empty(), Map.of(), OperationPresentation.defaults());
    }

    public static BackupRequest create(String callerId, String comment) {
        return new BackupRequest(
                callerId, Optional.ofNullable(comment), Map.of(), OperationPresentation.defaults());
    }

    public BackupRequest withParameter(String key, String value) {
        return withParameters(Map.of(key, value));
    }

    public BackupRequest withParameters(Map<String, String> additions) {
        return new BackupRequest(
                callerId,
                comment,
                ApiParameterSupport.merge(parameters, additions, RESERVED_PARAMETERS),
                presentation);
    }

    public BackupRequest withPresentation(OperationPresentation value) {
        return new BackupRequest(callerId, comment, parameters, value);
    }
}
