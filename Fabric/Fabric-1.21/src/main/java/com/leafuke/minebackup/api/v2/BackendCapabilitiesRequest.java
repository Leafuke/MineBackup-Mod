package com.leafuke.minebackup.api.v2;

/** Read-only protocol discovery; this request never initiates a world operation. */
public record BackendCapabilitiesRequest(String callerId) {
    public BackendCapabilitiesRequest {
        callerId = CallerId.normalize(callerId);
    }

    public static BackendCapabilitiesRequest create(String callerId) {
        return new BackendCapabilitiesRequest(callerId);
    }
}
