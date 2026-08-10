# Fabric 26.1 Configuration and Permissions

This reference applies only to MineBackup-Mod for Fabric on Minecraft
26.1-26.1.2. The desktop MineBackup/FolderRewind application has its own
configuration.

## Files and loading

- Global settings: `config/minebackup.properties`.
- World automation: `config/minebackup/worlds/<SHA-256>.properties`.
- Dedicated restore state and helper files: `config/minebackup/restart/`.

Global settings and the current world's automation file are loaded when the
Minecraft server starts. In singleplayer, opening a world starts its integrated
server. Restart the server or reopen the world after editing a global file.
`/mb auto start` and `/mb auto stop` write and apply the world file immediately.

MineBackup rewrites known global settings into canonical form. Do not store
unrelated custom keys in `minebackup.properties`.

## Global settings

| Key | Default | Accepted value | Applies to | Purpose |
|---|---:|---|---|---|
| `restore.countdownSeconds` | `10` | integer `0..300` | all environments | Delay before a current-world restore is submitted; `0` disables the countdown. |
| `lan.hostReopen.enabled` | `true` | `true` or `false` | LAN host | Reopen LAN after an integrated-server restore. |
| `lan.hostReopen.retryCount` | `6` | integer `1..30` | LAN host | Number of attempts to reopen the original LAN port. |
| `lan.hostReopen.retryIntervalTicks` | `40` | integer `10..200` | LAN host | Delay between reopen attempts, in server ticks. |
| `lan.hostReopen.allowRandomPortFallback` | `true` | `true` or `false` | LAN host | Try an available random port if the original port cannot be reopened. |
| `lan.clientReconnect.enabled` | `true` | `true` or `false` | LAN guest client | Automatically reconnect after the host restores and reopens LAN. |
| `lan.clientReconnect.initialDelayTicks` | `200` | integer `40..600` | LAN guest client | Delay before the first reconnect attempt. |
| `lan.clientReconnect.retryIntervalTicks` | `100` | integer `20..200` | LAN guest client | Delay between reconnect attempts. |
| `lan.clientReconnect.maxDurationTicks` | `1800` | integer `200..7200` | LAN guest client | Maximum reconnect attempt window. |
| `updateCheck.enabled` | `true` | `true` or `false` | client and server | Check for a newer MineBackup-Mod release. |
| `dedicatedRestore.mode` | `SIDECAR` | `SIDECAR` or `DISABLED` | dedicated server | Enable safe restore handoff to the bundled restart sidecar. |
| `dedicatedRestore.restartScript` | empty | path string | dedicated server | Explicit restart script. Empty uses automatic discovery in the server working directory. |
| `dedicatedRestore.sidecarStartTimeoutSeconds` | `5` | integer `1..60` | dedicated server | Time allowed for the sidecar to subscribe before shutdown. |
| `dedicatedRestore.worldReleaseTimeoutSeconds` | `8` | integer `1..120` | dedicated server | Time allowed for world files to become releasable. |
| `dedicatedRestore.operationTimeoutSeconds` | `3600` | integer `30..86400` | dedicated server | Maximum time for the handed-off restore operation. |

Out-of-range integers are clamped to the documented range. Missing, blank, or
malformed values use their defaults. See [Dedicated Restore](DEDICATED-RESTORE.md)
for restart script and process-manager requirements.

## World automation

Configure the hosted world with:

```text
/mb auto start <minutes>
/mb auto start <minutes> backup
/mb auto start <minutes> remind
/mb auto status
/mb auto stop
```

The first form selects `BACKUP`. The interval is a whole number of minutes from
`1` through `525600`. `BACKUP` submits a current-world hot backup. `REMIND`
sends all online players a chat reminder without contacting KnotLink; players
with backup permission also receive a clickable **Back up now** action. With no
players online, the reminder is logged only.

Each world file is managed by MineBackup and contains:

| Key | Value | Editing guidance |
|---|---|---|
| `world.identity` | normalized relative or absolute world path | Generated identity; do not edit. |
| `world.displayName` | world name | Diagnostic label only; it does not identify the world. |
| `automation.mode` | `OFF`, `BACKUP`, or `REMIND` | Prefer `/mb auto` commands. |
| `automation.intervalMinutes` | integer `1..525600` | Present only for `BACKUP` and `REMIND`. |

Worlds below the game directory use a normalized relative path, so moving the
whole instance preserves the plan. External worlds use a real absolute path.
Renaming or moving only the world creates a new identity whose automation is
off; the old file is retained. Invalid or mismatched files are disabled.

Only the current world's plan is scheduled. Server startup starts a fresh
interval; an absolute next-run time is never persisted. A successful
current-world backup, including a `NO_CHANGES` result, restarts the interval
from its completion time. Failed, cancelled, busy, or rejected backups do not.

Reminder mode is intended for worlds where an automatic backup might coincide
with an unload-sensitive redstone machine. MineBackup cannot determine a safe
machine state, and restoring a world necessarily unloads it. Reminder mode does
not make such machines restore-safe; it lets a player choose a suitable moment.

## Permissions

Fabric 26.1 uses native Minecraft permission atoms. The integrated-server world
owner and the server console are always allowed. Other callers are allowed by
`minebackup:command/admin`, the specific capability below, or the vanilla
moderator permission (`OP` level 2) as a compatibility fallback.

| Permission atom | Commands |
|---|---|
| `minebackup:command/backup` | `/mb save`, `/mb backup` |
| `minebackup:command/restore` | `/mb restore`, `/mb confirm`, `/mb stop` |
| `minebackup:command/browse` | `/mb list configs`, `/mb list folders`, `/mb list backups` |
| `minebackup:command/target_backup` | `/mb target backup` |
| `minebackup:command/target_restore` | `/mb target restore` |
| `minebackup:command/automation` | `/mb auto start`, `/mb auto stop`, `/mb auto status` |
| `minebackup:command/admin` | all MineBackup commands |

Help, command discovery, and completion are filtered by capability. Backup list
entries show a restore action only to callers with current-world restore
permission. MineBackup does not include a player-role or ACL editor; permission
management remains the server's responsibility.

## Upgrade behavior

The old `auto.currentWorld.intervalMinutes` global key is migrated once, after a
world is successfully opened. If that world has no plan, it becomes a `BACKUP`
plan; an existing world plan wins. The key is removed only after the world file
is written successfully. Older target-based keys such as `auto.configId` and
`auto.folder` remain disabled and produce a migration notice.

If `config/minebackup.properties` does not exist but the former
`config/minebackup-auto.properties` does, the former file is moved to the new
name before loading.

## Manual acceptance checklist

- Confirm the singleplayer owner can use commands without OP.
- Confirm a LAN owner, LAN OP, and LAN ordinary player receive the expected capabilities.
- Confirm dedicated-server console, OP2, and non-OP behavior.
- Start automation in world A, open world B and verify it remains off, then reopen A and verify its plan returns.
- Exercise both `BACKUP` and `REMIND`, including reminders with no players online.
- Complete a manual backup and verify `/mb auto status` shows a newly calculated next run.
