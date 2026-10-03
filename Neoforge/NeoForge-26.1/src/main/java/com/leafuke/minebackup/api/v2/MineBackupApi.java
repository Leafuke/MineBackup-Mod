package com.leafuke.minebackup.api.v2;

import com.leafuke.minebackup.MineBackup;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Stable in-process integration API v2; 3.4 adds compatible read-only diagnostics and restore cancellation. */
public interface MineBackupApi {
    int API_VERSION = 2;

    static MineBackupApi getInstance() {
        return MineBackup.api();
    }

    int apiVersion();

    OperationHandle<BackupResult> backupCurrent(BackupRequest request);

    default OperationHandle<ProtectedBackupResult> backupProtectedCurrent(BackupRequest request) {
        java.util.Objects.requireNonNull(request);
        return new CompletedOperationHandle<>(request.callerId(), ProtectedBackupResult.unsupported());
    }

    default OperationHandle<BackupProtectionResult> setBackupProtection(BackupProtectionRequest request) {
        java.util.Objects.requireNonNull(request);
        return new CompletedOperationHandle<>(request.callerId(), BackupProtectionResult.unsupported(request.backupId()));
    }

    default CompletionStage<BackupProtectionResult> queryBackupProtection(BackupProtectionRequest request) {
        java.util.Objects.requireNonNull(request);
        return java.util.concurrent.CompletableFuture.completedFuture(BackupProtectionResult.unsupported(request.backupId()));
    }

    OperationHandle<RestoreResult> restoreCurrent(RestoreRequest request);

    CompletionStage<BackupCatalogResult> listCurrentBackups(BackupCatalogRequest request);

    RuntimeStatus runtimeStatus();

    default CompletionStage<BackendCapabilitiesResult> backendCapabilities(BackendCapabilitiesRequest request) {
        java.util.Objects.requireNonNull(request, "request");
        return java.util.concurrent.CompletableFuture.completedFuture(
                BackendCapabilitiesResult.unavailable(BackendCapabilitiesResult.Outcome.UNSUPPORTED,
                        "This MineBackup implementation does not expose backend capabilities"));
    }

    default CompletionStage<BackendStatusResult> backendStatus(BackendStatusRequest request) {
        java.util.Objects.requireNonNull(request, "request");
        return java.util.concurrent.CompletableFuture.completedFuture(
                BackendStatusResult.unavailable(BackendStatusResult.Outcome.UNSUPPORTED, null));
    }

    /** A UUID is sufficient; callerId attributes the cancellation without enforcing ownership. */
    default RestoreCancelResult cancelRestore(RestoreCancelRequest request) {
        java.util.Objects.requireNonNull(request, "request");
        return RestoreCancelResult.UNSUPPORTED;
    }

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
        return CurrentWorldAutomationState.disabled(null);
    }
}
