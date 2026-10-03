package com.leafuke.minebackup.runtime;
import com.leafuke.minebackup.api.v2.*;
import java.util.UUID;
final class BackupProtectionHandle extends AbstractOperationHandle<BackupProtectionResult> {
    final BackupProtectionRequest request;
    BackupProtectionHandle(BackupProtectionRequest request) {
        super(UUID.randomUUID(), request.callerId(), OperationPhase.SUBMITTING); this.request = request;
    }
}
