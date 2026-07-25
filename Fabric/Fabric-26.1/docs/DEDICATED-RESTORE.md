# Dedicated-server restore operations

MineBackup can hot-backup the current dedicated world and can coordinate
`/mb restore` by transferring restore ownership to a restart sidecar. The
generic `/mb target restore` command remains disabled on dedicated servers so
it cannot bypass the current-world operation gate.

## Configuration

MineBackup writes `config/minebackup.properties`. If only
`config/minebackup-auto.properties` exists, it is moved atomically on first
load.

```properties
dedicatedRestore.mode=SIDECAR
dedicatedRestore.restartScript=
dedicatedRestore.sidecarStartTimeoutSeconds=5
dedicatedRestore.worldReleaseTimeoutSeconds=8
dedicatedRestore.operationTimeoutSeconds=3600
```

Set `mode=DISABLED` to reject dedicated restores during preflight. A relative
`restartScript` is resolved against the server working directory; an absolute
path is allowed. It must name a regular file.

When the path is blank, MineBackup searches the working directory:

- Windows: `start.bat`, `start.cmd`, `run.bat`, `run.cmd`
- Linux/macOS: `start.sh`, `run.sh`

Exactly one candidate must exist. Zero or multiple candidates reject the
restore before contacting FolderRewind, kicking players, or stopping the
server. Windows batch files run through `cmd.exe /c`; Unix shell scripts run
through `/bin/sh`; another explicitly configured file must be executable and
is started directly.

MineBackup does not retry a failed script. Do not combine this feature with a
panel or wrapper that immediately restarts the JVM after exit: that wrapper can
race FolderRewind. Disable its immediate restart, or configure a script that
launches one server instance and returns.

## Handoff files and recovery

Cross-process state lives in `config/minebackup/restart/`, never inside the
world:

- `active.properties`: atomic active session
- `sidecar.ready`: proof that the sidecar subscribed successfully
- `last-result.properties`: most recent terminal cross-process result

On startup MineBackup removes transient files and exposes the retained result
through `runtimeStatus()`. It never executes a retained script again.

If the final state is `RESTART_FAILED`, repair the script and start the server
manually. If it is `UNCERTAIN`, first verify that FolderRewind is no longer
writing and that the world is internally consistent; then start manually.
Never assume silence means FolderRewind failed safely.

## Operational flow

1. MineBackup validates mode, restart script, session directory, and operation
   gate before submitting `RESTORE current_save`.
2. On matching `pre_hot_restore`, it validates again and saves players and all
   worlds.
3. It atomically persists the session, launches the sidecar, and waits for its
   ready marker.
4. Only then does it broadcast preparation, disconnect players, complete the
   in-process handle as `RESTART_HANDOFF_ACCEPTED`, and call
   `server.halt(false)`.
5. The sidecar requires the parent JVM to exit and three stable world-release
   probes before acknowledging world release once.
6. An explicit success, failure, or cancellation permits one script launch.
   An unknown outcome keeps the server offline.
