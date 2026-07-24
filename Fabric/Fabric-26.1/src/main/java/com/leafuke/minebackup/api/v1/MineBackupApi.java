package com.leafuke.minebackup.api.v1;

import com.leafuke.minebackup.MineBackup;

import java.time.Duration;
import java.util.Optional;

/** Stable in-process integration API for MineBackup 3.1 and later. */
public interface MineBackupApi {
    int API_VERSION = 1;

    static MineBackupApi getInstance() {
        return MineBackup.api();
    }

    int apiVersion();

    OperationHandle<BackupResult> backupCurrent(BackupRequest request);

    RestoreHandle restoreCurrent(RestoreRequest request);

    Optional<RestoreHandle> pendingRestore();

    AutoBackupResult startAutomaticBackup(Duration interval);

    AutoBackupResult stopAutomaticBackup();

    AutoBackupState automaticBackupState();
}
