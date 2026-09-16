package com.leafuke.minebackup.api.v2;

import java.util.Objects;

public record OperationFailure(Code code, String message) {
    public OperationFailure {
        Objects.requireNonNull(code, "code");
        message = message == null ? "" : message;
    }

    public enum Code {
        BUSY,
        NO_ACTIVE_SERVER,
        COMMUNICATION_ERROR,
        BACKEND_REJECTED,
        BACKEND_CANCELLED,
        SERVER_STOPPED,
        SAVE_TIMEOUT,
        RESTORE_FAILED,
        REJOIN_FAILED,
        CONFIG_WRITE_FAILED,
        RESTART_UNAVAILABLE,
        SIDECAR_START_FAILED,
        WORLD_RELEASE_TIMEOUT,
        PROTOCOL_ERROR,
        RESTART_SCRIPT_FAILED,
        CROSS_PROCESS_STATE_UNCERTAIN
    }
}
