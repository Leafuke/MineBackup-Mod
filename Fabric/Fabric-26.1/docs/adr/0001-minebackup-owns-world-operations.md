# MineBackup owns current-world operations

Integration mods depend on MineBackup's versioned in-process API instead of copying its KnotLink, hot-save, restore, and rejoin implementation. This keeps one owner for destructive world-operation state while allowing callers such as Just Enough Accident to receive typed completion results.
