# MineBackup API v1

MineBackup API v1 is included in the main mod JAR under
`com.leafuke.minebackup.api.v1`. Integration mods should declare MineBackup
3.1.0 or newer as a required dependency.

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
