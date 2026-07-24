package com.leafuke.minebackup.runtime;

import com.leafuke.minebackup.api.v1.OperationPhase;
import com.leafuke.minebackup.api.v1.RestoreControlResult;
import com.leafuke.minebackup.api.v1.RestoreHandle;
import com.leafuke.minebackup.api.v1.RestoreRequest;
import com.leafuke.minebackup.api.v1.RestoreResult;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

final class RestoreOperationHandle
        extends AbstractOperationHandle<RestoreResult>
        implements RestoreHandle {
    private final RestoreRequest request;
    private Supplier<Duration> remaining = () -> Duration.ZERO;
    private Supplier<RestoreControlResult> confirm = () -> RestoreControlResult.NOT_PENDING;
    private Supplier<RestoreControlResult> cancel = () -> RestoreControlResult.NOT_PENDING;

    RestoreOperationHandle(UUID id, RestoreRequest request, OperationPhase initialPhase) {
        super(id, request.callerId(), initialPhase);
        this.request = request;
    }

    RestoreRequest request() {
        return request;
    }

    void bindControls(
            Supplier<Duration> remaining,
            Supplier<RestoreControlResult> confirm,
            Supplier<RestoreControlResult> cancel) {
        this.remaining = remaining;
        this.confirm = confirm;
        this.cancel = cancel;
    }

    @Override
    public Duration remaining() {
        return remaining.get();
    }

    @Override
    public RestoreControlResult confirm() {
        return confirm.get();
    }

    @Override
    public RestoreControlResult cancel() {
        return cancel.get();
    }
}
