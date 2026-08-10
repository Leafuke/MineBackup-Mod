package com.leafuke.minebackup.command;

import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.Permissions;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandPermissionsTest {
    @Test
    void capabilitiesExposeStableNativePermissionAtoms() {
        assertEquals(
                "minebackup:command/backup",
                ((Permission.Atom) CommandCapability.BACKUP.permission()).id().toString());
        assertEquals(
                "minebackup:command/admin",
                ((Permission.Atom) CommandPermissions.ADMIN).id().toString());
    }

    @Test
    void ownerAdminSpecificCapabilityAndModeratorAreAccepted() {
        assertTrue(allowed(true, Set.of(), CommandCapability.RESTORE));
        assertTrue(allowed(false, Set.of(CommandPermissions.ADMIN), CommandCapability.RESTORE));
        assertTrue(allowed(
                false,
                Set.of(CommandCapability.RESTORE.permission()),
                CommandCapability.RESTORE));
        assertTrue(allowed(
                false,
                Set.of(Permissions.COMMANDS_MODERATOR),
                CommandCapability.RESTORE));
    }

    @Test
    void unrelatedCapabilityAndUnavailableServerAreRejected() {
        assertFalse(allowed(
                false,
                Set.of(CommandCapability.BACKUP.permission()),
                CommandCapability.RESTORE));
        assertFalse(CommandPermissions.evaluate(
                false,
                true,
                permission -> true,
                CommandCapability.BACKUP));
    }

    @Test
    void everyCapabilityKeepsModeratorFallback() {
        Set<Permission> moderator = new HashSet<>();
        moderator.add(Permissions.COMMANDS_MODERATOR);
        for (CommandCapability capability : CommandCapability.values()) {
            assertTrue(allowed(false, moderator, capability), capability.name());
        }
    }

    private static boolean allowed(
            boolean owner,
            Set<Permission> granted,
            CommandCapability capability) {
        return CommandPermissions.evaluate(true, owner, granted::contains, capability);
    }
}
