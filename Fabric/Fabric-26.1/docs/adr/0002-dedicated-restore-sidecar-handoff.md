# ADR 0002: Dedicated restore uses a sidecar handoff

## Status

Accepted for MineBackup 3.1.0 / API v2.

## Context

A dedicated server cannot safely replace its active world files, and a server
wrapper that restarts immediately when the JVM exits can race FolderRewind.
The existing FolderRewind protocol already has a world-release
acknowledgement, explicit restore terminal events, and a 30-second rejoin
result window. It has no event that proves a newly launched Minecraft server
has reached `SERVER_STARTED`.

## Decision

MineBackup starts a pure-JDK sidecar before stopping the server. The sidecar
subscribes before writing its ready marker, requires three consecutive probes
showing the parent JVM and world files released, and sends
`WORLD_SAVE_AND_EXIT_COMPLETE` once. It launches exactly one restart script
only after `restore_finished=success`, `restore_finished=failure`, or
`restore_cancelled`.

For a successful restore, `REJOIN_RESULT=success` means that the configured
restart-script process was successfully created. It does **not** mean
Minecraft reached `SERVER_STARTED` or that players can already connect. This
keeps the unchanged 30-second protocol usable for large servers.

Communication loss, an absent terminal event, or total timeout is recorded as
`UNCERTAIN` and does not launch the server. This fail-closed behavior avoids
starting Minecraft while FolderRewind may still be writing world files.

## Consequences

- `RestoreResult.RESTART_HANDOFF_ACCEPTED` is an ownership-transfer result, not
  a restore result.
- Explicit restore failure and cancellation still launch the script so the
  original or rolled-back world can return online; their persisted outcome
  remains failure or cancellation.
- Restart-script creation is never retried automatically.
- Operators must disable immediate restart in panels or wrapper loops, or make
  the configured script launch exactly one server instance.
- A real FolderRewind/server exercise remains an operator acceptance step
  because it depends on the external application and local startup scripts.
