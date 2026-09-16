package com.leafuke.minebackup.runtime;

import com.leafuke.minebackup.api.v2.BackupCatalogResult;
import com.leafuke.minebackup.api.v2.OperationPhase;

import java.util.UUID;

final class CatalogOperationHandle extends AbstractOperationHandle<BackupCatalogResult> {
    CatalogOperationHandle(UUID id, String callerId, OperationPhase initialPhase) {
        super(id, callerId, initialPhase);
    }
}
