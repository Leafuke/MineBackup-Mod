package com.leafuke.minebackup.runtime;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Owns delivery of MineBackup messages to the active server. Keeping delivery
 * behind this boundary lets API requests select a feedback policy without
 * leaking Minecraft types into the public API.
 */
final class FeedbackRouter {
    private final Supplier<MinecraftServer> server;

    FeedbackRouter(Supplier<MinecraftServer> server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    void broadcast(Component message) {
        Objects.requireNonNull(message, "message");
        MinecraftServer current = server.get();
        if (current != null) {
            current.executeIfPossible(() ->
                    current.getPlayerList().broadcastSystemMessage(message, false));
        }
    }

    void broadcastOnServer(MinecraftServer current, Component message) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(message, "message");
        current.getPlayerList().broadcastSystemMessage(message, false);
    }
}
