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
            if (cause instanceof com.leafuke.minebackup.interop.InteropWire.Rejection r) {
                var code =
                        switch (r.code()) {
                            case "backend_offline" -> OperationFailure.Code.BACKEND_OFFLINE;
                            case "busy" -> OperationFailure.Code.BUSY;
                            case "unsupported_option", "protocol_mismatch" ->
                                    OperationFailure.Code.UNSUPPORTED;
                            case "result_unknown", "recovery_required" ->
                                    OperationFailure.Code.CROSS_PROCESS_STATE_UNCERTAIN;
                            default -> OperationFailure.Code.BACKEND_REJECTED;
                        };
                return new OperationFailure(code, r.code());
            }
            if (cause instanceof java.util.concurrent.TimeoutException)
                return new OperationFailure(
                        OperationFailure.Code.RESPONSE_TIMEOUT, "response_timeout");
            if (cause instanceof java.net.ConnectException)
                return new OperationFailure(
                        OperationFailure.Code.KNOTLINK_UNREACHABLE, "relay_unreachable");
            if (cause instanceof KnotLinkFrameException || cause instanceof KnotLinkProtocolException) {
                return new OperationFailure(OperationFailure.Code.PROTOCOL_ERROR, cause.getMessage());
            }
        }
        return new OperationFailure(OperationFailure.Code.COMMUNICATION_ERROR, error.getMessage());
    }
}
