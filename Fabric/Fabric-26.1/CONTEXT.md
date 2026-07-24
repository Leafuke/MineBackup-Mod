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
