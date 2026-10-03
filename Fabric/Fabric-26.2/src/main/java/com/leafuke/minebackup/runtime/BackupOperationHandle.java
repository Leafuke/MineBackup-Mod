package com.leafuke.minebackup.runtime;

import com.leafuke.minebackup.api.v2.BackupResult;
import com.leafuke.minebackup.api.v2.BackupRequest;
import com.leafuke.minebackup.api.v2.OperationPhase;

import java.util.UUID;

final class BackupOperationHandle extends AbstractOperationHandle<BackupResult> {
    private final BackupRequest request;
    boolean protectedBackup;
    boolean protectionConfirmed;
    boolean reused;

    com.leafuke.minebackup.api.v2.OperationHandle<com.leafuke.minebackup.api.v2.ProtectedBackupResult> protectedView() {
        var self = this;
        return new com.leafuke.minebackup.api.v2.OperationHandle<>() {
            public UUID id() { return self.id(); }
            public String callerId() { return self.callerId(); }
            public OperationPhase phase() { return self.phase(); }
            public java.util.concurrent.CompletionStage<com.leafuke.minebackup.api.v2.ProtectedBackupResult> completion() {
                return self.completion().thenApply(result -> {
                    var outcome = switch (result.outcome()) {
                        case CREATED -> reused ? com.leafuke.minebackup.api.v2.ProtectedBackupResult.Outcome.REUSED : com.leafuke.minebackup.api.v2.ProtectedBackupResult.Outcome.CREATED;
                        case CANCELLED -> com.leafuke.minebackup.api.v2.ProtectedBackupResult.Outcome.CANCELLED;
                        case REJECTED -> com.leafuke.minebackup.api.v2.ProtectedBackupResult.Outcome.REJECTED;
                        default -> com.leafuke.minebackup.api.v2.ProtectedBackupResult.Outcome.FAILED;
                    };
                    if (result.failure().map(f -> f.code() == com.leafuke.minebackup.api.v2.OperationFailure.Code.UNSUPPORTED).orElse(false))
                        outcome = com.leafuke.minebackup.api.v2.ProtectedBackupResult.Outcome.UNSUPPORTED;
                    return new com.leafuke.minebackup.api.v2.ProtectedBackupResult(outcome, result.backupId(), protectionConfirmed && result.outcome() == BackupResult.Outcome.CREATED, result.failure());
                });
            }
        };
    }

    BackupOperationHandle(UUID id, BackupRequest request, OperationPhase initialPhase) {
        super(id, request.callerId(), initialPhase);
        this.request = request;
    }

    BackupRequest request() {
        return request;
    }
}
