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
provides only archive names. MineBackup best-effort parses standard FolderRewind
file names to populate `createdAt` and `comment`; integrations must tolerate
absent metadata for legacy or unrecognized names. Catalog order is undefined.
Unsafe backend names fail the entire result with `PROTOCOL_ERROR`.

`runtimeStatus()` reports the environment, current operation, read-only
automatic-backup schedule, dedicated restore availability, and the last
persisted dedicated handoff result. API consumers cannot confirm or cancel
another caller's restore and cannot modify `/mb auto`.

`currentWorldAutomation()` is the complete read-only automation view. It
returns `CurrentWorldAutomationState`, including current-world availability,
the optional display name, `CurrentWorldAutomationMode` (`OFF`, `BACKUP`, or
`REMIND`), the interval, and the next trigger time. The method has a default
implementation so API v2 implementations compiled before this addition remain
binary compatible.

The older `RuntimeStatus.automaticBackup()` and `AutoBackupState` members remain
unchanged. They report enabled only for `BACKUP`; `REMIND` deliberately appears
disabled in this legacy view. Consumers that need to distinguish reminders
must call `currentWorldAutomation()`.

```java
CurrentWorldAutomationState automation = api.currentWorldAutomation();
if (automation.mode() == CurrentWorldAutomationMode.REMIND) {
    automation.nextRun().ifPresent(next -> renderReminderTime(next));
}
```

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

This does not modify the administrator's world-bound `/mb auto` plan. The caller owns
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

## MineBackup 3.4.0 compatible additions

All eight maintained mod builds expose `backendCapabilities`, `backendStatus`, and `cancelRestore`. `API_VERSION` stays 2 and existing public record constructors are unchanged. New methods have `UNSUPPORTED` defaults so older implementations remain loadable.

Backend queries are read-only and bypass the operation gate. Their origin must still be an active world when the reply arrives. Capability declarations and communication observations do not establish plugin or current-world readiness. Status fields are optional, and signal-channel connectivity is independent from query success.

`cancelRestore(RestoreCancelRequest.create(callerId, operationUuid))` atomically cancels a matching restore only in `COUNTING_DOWN`. UUID possession is sufficient; callerId is attribution, not ownership. The result is `CANCELLED`, `NOT_PENDING`, `ALREADY_SUBMITTED`, or `UNSUPPORTED`. Cancellation never stops a submitted backend restore. Administrators retain the existing `/mb stop` behavior.

See [backend diagnostics and cancellation examples](BACKEND-CAPABILITIES.md) for contracts and migration notes. `OperationFailure.message()` retains technical detail; user interfaces should localize the structured code. An addon requiring these newly added types needs MineBackup >=3.4.0 or explicit legacy linkage handling.


## 3.4.0 companion build: protected backups

The eight maintained mod targets expose these additive API v2 methods without changing `API_VERSION` or the mod version:

```java
api.setBackupProtection(BackupProtectionRequest.set("addon:pin", backupId, true));
api.queryBackupProtection(BackupProtectionRequest.query("addon:pin", backupId));
api.backupProtectedCurrent(BackupRequest.create("addon:record", "Before the expedition"));
```

`setBackupProtection` returns `OperationHandle<BackupProtectionResult>`; query returns `CompletionStage<BackupProtectionResult>`. A successful result has an explicit `important` value. Rejection, unsupported implementations, communication errors and missing targets never become `false`. These methods operate only on the current bound world, share its operation gate, and query backend capabilities first. Callers enforce player authorization. Cancellation attribution does not confer authorization.

`backupProtectedCurrent` returns `OperationHandle<ProtectedBackupResult>`. `CREATED` and `REUSED` require a named backup and confirmed protection; other outcomes are `CANCELLED`, `REJECTED`, `FAILED`, `UNSUPPORTED`. A reused backup keeps its original filename and version comment; the new request comment does not rename it. Full/smart compression remains configurable, but the effective source must be complete and unfiltered. No fallback or retry is performed. Ordinary `backupCurrent` remains ordinary; the control parameter `protect` is reserved for the protected API.

FolderRewind uses `BACKUP;protect=true`, `MARK_IMPORTANT`, and `GET_IMPORTANCE`. Protection is committed with the version/history transaction. Per-source `backup_success` is not a protected receipt; the final correlated `command_completed` must contain `result=created|reused`, `file`, and `important=true`. Pin queries describe retention annotations, not restore readiness. Completion callbacks need not run on a Minecraft thread.

New methods have default unsupported implementations. An old JAR may not contain the new types at all: check method availability before loading an adapter that references them, as Time-Machine does. The same 3.4.0 number does not prove support.

The public ID is still the backend filename. Backend compaction can replace or collide filenames; this change neither fixes that limitation nor provides permanent return tickets. No new branch/history-graph API is introduced.


## 3.4.0 companion build: per-request countdown and cached discovery

```java
var request = RestoreRequest.backup("addon:travel", backupId).withCountdownSeconds(5);
api.restoreCurrent(request);
api.backendCapabilities(BackendCapabilitiesRequest.create("addon:preview"));
api.backendCapabilities(BackendCapabilitiesRequest.create("addon:refresh").refreshed());
```

`RestoreRequest.countdownSeconds()` is optional. Its original six-argument constructor and factories remain available. An absent value uses the configured countdown; accepted operations freeze the effective value. Overrides accept 0–300 whole seconds, with zero submitting immediately. `immediate()` clears the override; a subsequent `withCountdownSeconds(...)` switches back to the specified countdown. Other copy methods preserve it. This is a local scheduling option, never a FolderRewind parameter or a global configuration edit.

Capabilities share a world/connection-scoped cache with protected backup operations. Concurrent discovery coalesces; cancelling or timing out a caller's returned future cannot cancel the shared discovery. Successful snapshots remain cached until world/connection replacement or explicit refresh. Failures are throttled for five seconds; refresh bypasses this throttle. Refresh during an existing discovery joins that discovery. There is no periodic refresh and no automatic write retry.

`BackendCapabilitiesResult.generation()` distinguishes refreshed sessions even when the manifest is identical. The original four-argument constructor remains available (generation zero). A preview and confirmation should require the same generation and declarations. `BackendCapabilitiesRequest` preserves its original one-argument constructor. Signal disconnect/reconnect invalidates the cache; if the desktop backend changes without a detectable transport change, use explicit refresh.

The mod version and API major remain 3.4.0 and 2. Old same-version JARs may lack these additions. Probe method presence before using a typed countdown adapter; do not infer support from the version number.
