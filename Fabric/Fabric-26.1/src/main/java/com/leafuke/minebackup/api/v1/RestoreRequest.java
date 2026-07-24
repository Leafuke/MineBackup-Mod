package com.leafuke.minebackup.api.v1;

import java.util.Objects;
import java.util.Optional;

public record RestoreRequest(
        String callerId,
        Optional<String> fileName,
        RestoreExecutionPolicy executionPolicy) {
    public RestoreRequest {
        callerId = BackupRequest.requireCallerId(callerId);
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(executionPolicy, "executionPolicy");
        fileName = fileName.map(String::trim).filter(value -> !value.isEmpty());
    }

    public static RestoreRequest latest(String callerId) {
        return new RestoreRequest(
                callerId,
                Optional.empty(),
                RestoreExecutionPolicy.CONFIGURED_COUNTDOWN);
    }

    public static RestoreRequest file(String callerId, String fileName) {
        return new RestoreRequest(
                callerId,
                Optional.ofNullable(fileName),
                RestoreExecutionPolicy.CONFIGURED_COUNTDOWN);
    }

    public RestoreRequest immediate() {
        return new RestoreRequest(callerId, fileName, RestoreExecutionPolicy.IMMEDIATE);
    }
}
