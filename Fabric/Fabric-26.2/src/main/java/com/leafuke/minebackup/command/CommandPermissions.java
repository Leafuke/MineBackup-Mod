package com.leafuke.minebackup.command;

import com.leafuke.minebackup.MineBackup;
import com.mojang.authlib.GameProfile;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.Permissions;

import java.util.function.Predicate;

public final class CommandPermissions {
    public static final Permission ADMIN = Permission.Atom.create(
            Identifier.fromNamespaceAndPath(MineBackup.MOD_ID, "command/admin"));

    private CommandPermissions() {
    }

    public static boolean canUse(CommandSourceStack source, CommandCapability capability) {
        MinecraftServer server = source.getServer();
        if (server == null) {
            return false;
        }
        return evaluate(
                true,
                isSingleplayerOwner(server, source.getPlayer()),
                source.permissions()::hasPermission,
                capability);
    }

    public static boolean hasAnyAccess(CommandSourceStack source) {
        for (CommandCapability capability : CommandCapability.values()) {
            if (canUse(source, capability)) {
                return true;
            }
        }
        return false;
    }

    static boolean evaluate(
            boolean serverAvailable,
            boolean singleplayerOwner,
            Predicate<Permission> permissionCheck,
            CommandCapability capability) {
        if (!serverAvailable) {
            return false;
        }
        if (singleplayerOwner) {
            return true;
        }
        return permissionCheck.test(ADMIN)
                || permissionCheck.test(capability.permission())
                || permissionCheck.test(Permissions.COMMANDS_MODERATOR);
    }

    private static boolean isSingleplayerOwner(MinecraftServer server, ServerPlayer player) {
        if (player == null) {
            return false;
        }
        GameProfile owner = server.getSingleplayerProfile();
        return owner != null && owner.id().equals(player.getGameProfile().id());
    }
}
