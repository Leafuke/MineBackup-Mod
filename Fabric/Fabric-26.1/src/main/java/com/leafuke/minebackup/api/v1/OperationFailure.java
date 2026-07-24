package com.leafuke.minebackup.api.v1;

import java.util.Objects;

public record OperationFailure(Code code, String message) {
    public OperationFailure {
        Objects.requireNonNull(code, "code");
        message = message == null ? "" : message;
    }

    public enum Code {
        BUSY,
        NO_ACTIVE_SERVER,
        UNSUPPORTED_DEDICATED_SERVER,
        COMMUNICATION_ERROR,
        BACKEND_REJECTED,
        BACKEND_CANCELLED,
        SERVER_STOPPED,
        SAVE_TIMEOUT,
        RESTORE_FAILED,
        REJOIN_FAILED,
        CONFIG_WRITE_FAILED
    }
}
