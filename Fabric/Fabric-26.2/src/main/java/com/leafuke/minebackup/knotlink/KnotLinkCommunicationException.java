package com.leafuke.minebackup.knotlink;

import com.leafuke.minebackup.api.v2.OperationFailure;
import com.leafuke.minebackup.knotlink.protocol.KnotLinkProtocolException;
import com.leafuke.minebackup.knotlink.sdk.KnotLinkFrameException;
import java.io.IOException;
import java.util.Objects;

/** Carries the stage-specific diagnosis while retaining the original transport cause. */
public final class KnotLinkCommunicationException extends IOException {
    private final OperationFailure.Code code;

    public KnotLinkCommunicationException(OperationFailure.Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public OperationFailure.Code code() { return code; }

    public static OperationFailure failure(Throwable error) {
        Objects.requireNonNull(error, "error");
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof KnotLinkCommunicationException communication) {
                return new OperationFailure(communication.code(), communication.getMessage());
            }
            if (cause instanceof KnotLinkFrameException || cause instanceof KnotLinkProtocolException) {
                return new OperationFailure(OperationFailure.Code.PROTOCOL_ERROR, cause.getMessage());
            }
        }
        return new OperationFailure(OperationFailure.Code.COMMUNICATION_ERROR, error.getMessage());
    }
}
