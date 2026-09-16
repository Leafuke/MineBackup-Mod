package com.leafuke.minebackup.dedicated;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record DedicatedRestoreSession(
        UUID requestId,
        String callerId,
        String worldId,
        Path worldPath,
        Path workingDirectory,
        List<String> restartCommand,
        long parentPid,
        int worldReleaseTimeoutSeconds,
        int operationTimeoutSeconds,
        State state,
        Instant updatedAt,
        String detail) {
    public DedicatedRestoreSession {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(callerId, "callerId");
        Objects.requireNonNull(worldId, "worldId");
        worldPath = Objects.requireNonNull(worldPath, "worldPath").toAbsolutePath().normalize();
        workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory")
                .toAbsolutePath()
                .normalize();
        restartCommand = List.copyOf(restartCommand);
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(updatedAt, "updatedAt");
        detail = detail == null ? "" : detail;
    }

    public DedicatedRestoreSession withState(State value, String valueDetail) {
        return new DedicatedRestoreSession(
                requestId,
                callerId,
                worldId,
                worldPath,
                workingDirectory,
                restartCommand,
                parentPid,
                worldReleaseTimeoutSeconds,
                operationTimeoutSeconds,
                value,
                Instant.now(),
                valueDetail);
    }

    public enum State {
        PREPARED,
        READY,
        WAITING_FOR_RELEASE,
        RELEASE_ACKNOWLEDGED,
        RESTORE_SUCCEEDED,
        RESTORE_FAILED,
        RESTORE_CANCELLED,
        RESTART_STARTED,
        RESTART_FAILED,
        UNCERTAIN
    }
}
