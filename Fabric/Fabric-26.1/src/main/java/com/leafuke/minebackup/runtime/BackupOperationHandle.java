package com.leafuke.minebackup.runtime;

import com.leafuke.minebackup.api.v1.BackupResult;
import com.leafuke.minebackup.api.v1.OperationPhase;

import java.util.UUID;

final class BackupOperationHandle extends AbstractOperationHandle<BackupResult> {
    BackupOperationHandle(UUID id, String callerId, OperationPhase initialPhase) {
        super(id, callerId, initialPhase);
    }
}
