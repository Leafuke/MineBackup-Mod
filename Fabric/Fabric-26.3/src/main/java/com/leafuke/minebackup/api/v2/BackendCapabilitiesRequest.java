package com.leafuke.minebackup.api.v2;

/** Read-only protocol discovery; this request never initiates a world operation. */
public record BackendCapabilitiesRequest(String callerId, boolean refresh) {
    public BackendCapabilitiesRequest(String callerId) { this(callerId, false); }

    public BackendCapabilitiesRequest refreshed() { return new BackendCapabilitiesRequest(callerId, true); }

    public BackendCapabilitiesRequest {
        callerId = CallerId.normalize(callerId);
    }

    public static BackendCapabilitiesRequest create(String callerId) {
        return new BackendCapabilitiesRequest(callerId);
    }
}
