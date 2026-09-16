package com.leafuke.minebackup.runtime;

import com.leafuke.minebackup.api.v2.BackupResult;
import com.leafuke.minebackup.api.v2.BackupRequest;
import com.leafuke.minebackup.api.v2.OperationPhase;

import java.util.UUID;

final class BackupOperationHandle extends AbstractOperationHandle<BackupResult> {
    private final BackupRequest request;

    BackupOperationHandle(UUID id, BackupRequest request, OperationPhase initialPhase) {
        super(id, request.callerId(), initialPhase);
        this.request = request;
    }

    BackupRequest request() {
        return request;
    }
}
