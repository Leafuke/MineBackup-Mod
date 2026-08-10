package com.leafuke.minebackup.api.v2;

import com.leafuke.minebackup.MineBackup;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Stable in-process integration API for MineBackup 3.1. */
public interface MineBackupApi {
    int API_VERSION = 2;

    static MineBackupApi getInstance() {
        return MineBackup.api();
    }

    int apiVersion();

    OperationHandle<BackupResult> backupCurrent(BackupRequest request);

    OperationHandle<RestoreResult> restoreCurrent(RestoreRequest request);

    CompletionStage<BackupCatalogResult> listCurrentBackups(BackupCatalogRequest request);

    RuntimeStatus runtimeStatus();

    default CurrentWorldAutomationState currentWorldAutomation() {
        RuntimeStatus status = runtimeStatus();
        if (!status.currentWorldAvailable()) {
            return CurrentWorldAutomationState.unavailable();
        }
        AutoBackupState legacy = status.automaticBackup();
        if (legacy.enabled()) {
            return new CurrentWorldAutomationState(
                    true,
                    Optional.empty(),
                    CurrentWorldAutomationMode.BACKUP,
                    legacy.interval(),
                    legacy.nextRun());
        }
        return status.currentWorldAvailable()
                ? CurrentWorldAutomationState.disabled(null)
                : CurrentWorldAutomationState.unavailable();
    }
}
