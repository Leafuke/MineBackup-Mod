# MineBackup API v1

MineBackup API v1 is included in the main mod JAR under
`com.leafuke.minebackup.api.v1`. Integration mods should declare MineBackup

The API does not expose Fabric, Minecraft, chat, or KnotLink types. Completion
callbacks are not guaranteed to run on the game thread.

```java
MineBackupApi api = MineBackupApi.getInstance();
BackupRequest request = BackupRequest.create(
        "just_enough_accident",
        "Creeper about to explode");

api.backupCurrent(request).completion().thenAccept(result -> {
    if (result.outcome() == BackupResult.Outcome.CREATED) {
        result.fileName().ifPresent(fileName -> {
            // Schedule onto the Minecraft thread before creating chat UI.
            // RestoreRequest.file("just_enough_accident", fileName) restores
            // this exact archive and uses the configured safety countdown.
        });
    }
});
```

`NO_CHANGES` deliberately has no guaranteed archive name. A caller must not
claim that a new restore point was created in that case.

## Additional FolderRewind parameters

Backup and restore requests can append backend parameters without exposing
KnotLink types:

```java
BackupRequest backup = BackupRequest.create(
        "just_enough_accident",
        "Creeper about to explode")
        .withParameter("backup_mode", "incremental")
        .withParameter("compression_method", "zstd");

RestoreRequest restore = RestoreRequest.file(
        "just_enough_accident",
        "snapshot.7z")
        .withParameters(Map.of(
                "restore_mode", "safe",
                "verify_archive", "true"));
```

Parameter maps are defensively copied and exposed as immutable maps. Keys are
case-insensitive ASCII identifiers containing only letters, digits, and `_`;
they are normalized to lower case. Values are passed through unchanged and are
escaped by the protocol encoder.

MineBackup rejects attempts to override fields that it owns:

- For both operations: `cmd`, `from`, `request_id`, and `current_save`.
- For backups: `comment`.
- For restores: `file`.

Invalid keys, null keys or values, and reserved fields throw
`IllegalArgumentException` (or `NullPointerException` for nulls) when the
request is constructed. The parameters affect only the FolderRewind request;
they do not change MineBackup's mutual exclusion, save freeze, countdown, or
rejoin workflow.
