package com.leafuke.minebackup.api.v2;
import java.util.Objects;
/** A current-world target; querying ignores the requested flag. */
public record BackupProtectionRequest(String callerId, BackupId backupId, boolean important) {
    public BackupProtectionRequest { callerId = CallerId.normalize(callerId); Objects.requireNonNull(backupId); }
    public static BackupProtectionRequest query(String callerId, BackupId id) { return new BackupProtectionRequest(callerId, id, false); }
    public static BackupProtectionRequest set(String callerId, BackupId id, boolean important) { return new BackupProtectionRequest(callerId, id, important); }
}
