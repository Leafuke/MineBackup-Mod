package com.leafuke.minebackup.command;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.network.ServerPlayerEntity;

public final class CommandPermissions {
    private CommandPermissions() {
    }

    public static boolean canUse(ServerCommandSource source, CommandCapability capability) {
        MinecraftServer server = source.getServer();
        if (server == null) {
            return false;
        }
        if (server.isDedicated()) {
            return source.hasPermissionLevel(2);
        }
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            return false;
        }
        GameProfile owner = server.getHostProfile();
        return owner != null && owner.getId().equals(player.getGameProfile().getId());
    }

    public static boolean hasAnyAccess(ServerCommandSource source) {
        for (CommandCapability capability : CommandCapability.values()) {
            if (canUse(source, capability)) {
                return true;
            }
        }
        return false;
    }
}
