package com.leafuke.minebackup.command;

import net.minecraft.server.MinecraftServer;
import net.minecraft.commands.CommandSourceStack;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ServerPlayer;

public final class CommandPermissions {
    private CommandPermissions() {
    }

    public static boolean canUse(CommandSourceStack source, CommandCapability capability) {
        MinecraftServer server = source.getServer();
        if (server == null) {
            return false;
        }
        if (server.isDedicatedServer()) {
            return source.hasPermission(2);
        }
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return false;
        }
        GameProfile owner = server.getSingleplayerProfile();
        return owner != null && owner.id().equals(player.getGameProfile().id());
    }

    public static boolean hasAnyAccess(CommandSourceStack source) {
        for (CommandCapability capability : CommandCapability.values()) {
            if (canUse(source, capability)) {
                return true;
            }
        }
        return false;
    }
}
