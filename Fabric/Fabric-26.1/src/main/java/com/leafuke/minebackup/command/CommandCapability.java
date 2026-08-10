package com.leafuke.minebackup.command;

import com.leafuke.minebackup.MineBackup;
import net.minecraft.resources.Identifier;
import net.minecraft.server.permissions.Permission;

public enum CommandCapability {
    BACKUP("backup"),
    RESTORE("restore"),
    BROWSE("browse"),
    TARGET_BACKUP("target_backup"),
    TARGET_RESTORE("target_restore"),
    AUTOMATION("automation");

    private final Permission permission;

    CommandCapability(String path) {
        permission = Permission.Atom.create(Identifier.fromNamespaceAndPath(
                MineBackup.MOD_ID,
                "command/" + path));
    }

    public Permission permission() {
        return permission;
    }
}
