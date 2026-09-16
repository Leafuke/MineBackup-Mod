package com.leafuke.minebackup.api.v2;

import java.util.Objects;
import java.util.Optional;

public record RuntimeStatus(
        RuntimeEnvironment environment,
        boolean currentWorldAvailable,
        boolean dedicatedRestoreAvailable,
        Optional<String> dedicatedRestoreUnavailableReason,
        Optional<OperationSnapshot> activeOperation,
        AutoBackupState automaticBackup,
        Optional<DedicatedRestoreStatus> lastDedicatedRestore) {
    public RuntimeStatus {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(dedicatedRestoreUnavailableReason, "dedicatedRestoreUnavailableReason");
        Objects.requireNonNull(activeOperation, "activeOperation");
        Objects.requireNonNull(automaticBackup, "automaticBackup");
        Objects.requireNonNull(lastDedicatedRestore, "lastDedicatedRestore");
    }
}
