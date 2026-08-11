package com.leafuke.minebackup.command;

public enum CommandCapability {
    BACKUP("backup"),
    RESTORE("restore"),
    BROWSE("browse"),
    TARGET_BACKUP("target_backup"),
    TARGET_RESTORE("target_restore"),
    AUTOMATION("automation");

    private final String path;

    CommandCapability(String path) {
        this.path = path;
    }

    public String path() {
        return path;
    }
}
