package com.leafuke.minebackup.api.v2;
import java.util.Objects;
import java.util.Optional;
/** A durable, backend-confirmed pin; filenames retain the backend's existing lifetime limits. */
public record ProtectedBackupResult(Outcome outcome, Optional<BackupId> backupId, boolean important,
        Optional<OperationFailure> failure) {
    public enum Outcome { CREATED, REUSED, CANCELLED, REJECTED, FAILED, UNSUPPORTED }
    public ProtectedBackupResult {
        Objects.requireNonNull(outcome); Objects.requireNonNull(backupId); Objects.requireNonNull(failure);
        boolean success = outcome == Outcome.CREATED || outcome == Outcome.REUSED;
        if (success != important || success && (backupId.isEmpty() || failure.isPresent()))
            throw new IllegalArgumentException("Success requires a named protected backup");
    }
    public static ProtectedBackupResult unsupported() { return new ProtectedBackupResult(Outcome.UNSUPPORTED, Optional.empty(), false, Optional.empty()); }
}
