# MineBackup API v2

API v2 is shipped in `com.leafuke.minebackup.api.v2`. It replaces the
unreleased v1 API and deliberately exposes no Minecraft, Fabric, KnotLink, or
chat component types. Completion callbacks are not guaranteed to run on a game
thread.

```java
MineBackupApi api = MineBackupApi.getInstance();
BackupRequest request = BackupRequest.create(
        "just_enough_accidents:incident",
        "Creeper explosion")
        .withPresentation(OperationPresentation.callerManaged())
        .withParameter("compression_method", "zstd");

api.backupCurrent(request).completion().thenAccept(result -> {
    result.backupId().ifPresent(id -> {
        RestoreRequest restore = RestoreRequest.backup(
                "just_enough_accidents:incident", id);
        // Schedule Minecraft UI work onto the appropriate game thread.
    });
});
```

Caller identifiers are normalized to lower case, are at most 64 characters,
and may contain `a-z`, digits, `.`, `_`, `-`, and `:`. `BackupId` accepts only
one safe file-name segment; path separators, control characters, `.` and `..`
are rejected when a request is constructed.

## Feedback ownership

`FeedbackPolicy.DEFAULT` keeps MineBackup's normal broadcasts.
`CALLER_MANAGED` suppresses optional progress broadcasts for that request so an
integration can send its own messages. Safety-required kick and rejoin
surfaces remain active. An integration may override those surfaces with an
`OperationPresentation` template; missing templates use MineBackup defaults.

Stable slot arguments are:

| Slot | Arguments |
|---|---|
| `BACKUP_STARTED` | world |
| `BACKUP_SUCCEEDED` | world, backup |
| `BACKUP_FAILED` | world, error |
| `BACKUP_NO_CHANGES` | world |
| `RESTORE_COUNTDOWN_STARTED`, `RESTORE_COUNTDOWN_TICK` | seconds |
| `RESTORE_CONFIRM`, `RESTORE_CANCEL` | none |
| `RESTORE_PREPARING` | world, backup |
| `RESTORE_KICK`, `RESTORE_REJOIN` | world, backup |
| `RESTORE_SUCCEEDED` | world, backup |
| `RESTORE_FAILED` | world, backup, error |

## Read-only queries

`listCurrentBackups` participates in the current-world operation gate and
returns `BUSY` during backup or restore. The current FolderRewind protocol
provides only archive names, so metadata is empty and order is undefined.
Unsafe backend names fail the entire result with `PROTOCOL_ERROR`.

`runtimeStatus()` reports the environment, current operation, read-only
automatic-backup schedule, dedicated restore availability, and the last
persisted dedicated handoff result. API consumers cannot confirm or cancel
another caller's restore and cannot modify `/mb auto`.

`RestoreResult.Outcome.RESTART_HANDOFF_ACCEPTED` is dedicated-server-only. It
means MineBackup safely handed ownership to the sidecar; it does not mean the
world has been restored or the new server is online.

## Planned integration patterns

DeathRewind can own concise feedback while scheduling ordinary API calls:

```java
BackupRequest periodic = BackupRequest.create("deathrewind:periodic")
        .withPresentation(OperationPresentation.callerManaged());
scheduler.scheduleAtFixedRate(
        () -> api.backupCurrent(periodic),
        5, 5, TimeUnit.MINUTES);
```

This does not modify the administrator's `/mb auto` schedule. The caller owns
its timer and must avoid claiming a restore point when the result is
`NO_CHANGES`, `BUSY`, or failed.

Time Machine can populate a read-only browser without gaining access to
arbitrary FolderRewind targets:

```java
api.listCurrentBackups(BackupCatalogRequest.create("time_machine:browser"))
        .thenAccept(result -> {
            if (result.outcome() == BackupCatalogResult.Outcome.SUCCESS) {
                result.entries().forEach(entry -> render(entry.backupId()));
            }
        });
```

Catalog order is undefined under the current protocol. Integrations should sort
for display and tolerate absent time, size, and comment metadata.
