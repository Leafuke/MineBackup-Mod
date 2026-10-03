package com.leafuke.minebackup.api.v2;

import java.util.Objects;
import java.util.UUID;

/** The UUID identifies the restore to cancel; callerId is attribution, not ownership. */
public record RestoreCancelRequest(String callerId, UUID requestId) {
    public RestoreCancelRequest {
        callerId = CallerId.normalize(callerId);
        Objects.requireNonNull(requestId, "requestId");
    }

    public static RestoreCancelRequest create(String callerId, UUID requestId) {
        return new RestoreCancelRequest(callerId, requestId);
    }
}
