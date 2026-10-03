package com.leafuke.minebackup.api.v2;

/** Read-only communication diagnostics; never submits a world operation. */
public record BackendStatusRequest(String callerId) {
    public BackendStatusRequest {
        callerId = CallerId.normalize(callerId);
    }

    public static BackendStatusRequest create(String callerId) {
        return new BackendStatusRequest(callerId);
    }
}
