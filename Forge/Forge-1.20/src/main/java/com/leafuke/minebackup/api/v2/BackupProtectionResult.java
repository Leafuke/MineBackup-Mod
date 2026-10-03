package com.leafuke.minebackup.api.v2;
import java.util.Objects;
import java.util.Optional;
/** Pin state only. This does not assert restore readiness or permanent filename identity. */
public record BackupProtectionResult(Outcome outcome, BackupId backupId, Optional<Boolean> important,
        Optional<OperationFailure> failure) {
    public enum Outcome { SUCCESS, REJECTED, FAILED, UNSUPPORTED }
    public BackupProtectionResult {
        Objects.requireNonNull(outcome); Objects.requireNonNull(backupId);
        Objects.requireNonNull(important); Objects.requireNonNull(failure);
        if ((outcome == Outcome.SUCCESS) != important.isPresent()) throw new IllegalArgumentException("Only success has a known pin state");
    }
    public static BackupProtectionResult unsupported(BackupId id) { return new BackupProtectionResult(Outcome.UNSUPPORTED, id, Optional.empty(), Optional.empty()); }
}
