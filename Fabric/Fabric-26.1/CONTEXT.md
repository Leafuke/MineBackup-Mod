# MineBackup

MineBackup coordinates safe Minecraft world snapshots and restores with the FolderRewind companion application.

## Language

**Current-world operation**:
A backup or restore whose target is the Minecraft world currently hosted by this game process.
_Avoid_: Remote command, generic task

**Hot backup**:
A current-world backup taken after Minecraft saves the world and temporarily freezes automatic saving, without closing the world.
_Avoid_: Quick backup, live copy

**Pending restore**:
A restore selected for the current world but not yet submitted to FolderRewind, so it can still be confirmed or cancelled.
_Avoid_: Active restore, confirmation

**Active restore**:
A current-world restore already submitted to FolderRewind and no longer safely cancellable by MineBackup.
_Avoid_: Pending restore, rollback timer

**Automatic backup schedule**:
The single persisted interval that periodically requests a hot backup of whichever world is currently active.
_Avoid_: FolderRewind auto task, target schedule

**Dedicated restore handoff**:
The accepted transfer of a dedicated-server restore from the Minecraft JVM to
the MineBackup sidecar. Acceptance means the sidecar is subscribed, its
session is durable, and shutdown can begin; it does not mean restore completed.
_Avoid_: Restore success, server restarted

**Restart sidecar**:
A pure-JDK child process that waits for the server JVM and world files to be
released, coordinates the existing FolderRewind restore protocol, and launches
one configured restart script after an explicit terminal signal.
_Avoid_: Watchdog, daemon, server wrapper

**Explicit terminal signal**:
`restore_finished=success`, `restore_finished=failure`, or
`restore_cancelled`. Only these signals authorize the sidecar to launch the
restart script. A disconnect or timeout is an uncertain result and keeps the
server offline.
_Avoid_: Silence means failure, best-effort restart

**Caller-managed feedback**:
A request policy that suppresses MineBackup's optional operation broadcasts so
the integration mod can own its wording. Safety-required kick and rejoin
surfaces remain present and may be customized with stable message templates.
_Avoid_: Disable all messages
