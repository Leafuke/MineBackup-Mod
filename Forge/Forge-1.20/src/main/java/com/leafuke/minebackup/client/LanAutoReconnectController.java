package com.leafuke.minebackup.client;

import com.leafuke.minebackup.MineBackup;
import com.leafuke.minebackup.config.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

public final class LanAutoReconnectController {
    private static final String RESTORE_KICK_KEY = "minebackup.message.restore.kick";

    private static ServerData lastLanServer;
    private static String lastLanAddress;
    private static boolean reconnecting;
    private static int waitTicks;
    private static int elapsedTicks;
    private static int attempts;

    private LanAutoReconnectController() {
    }

    public static void onClientTick(Minecraft client) {
        if (client.level != null) {
            trackCurrentServer(client);
            return;
        }

        Config.ClientReconnect config = Config.get().clientReconnect();
        if (!config.enabled()) {
            clearAll();
            return;
        }
        if (client.getSingleplayerServer() != null) {
            clearAll();
            return;
        }
        if (reconnecting) {
            tickReconnect(client, config);
            return;
        }
        if (client.screen instanceof ConnectScreen) {
            return;
        }
        if (!(client.screen instanceof DisconnectedScreen disconnectedScreen)) {
            clearAll();
            return;
        }
        if (lastLanServer == null || lastLanAddress == null) {
            return;
        }
        if (!containsTranslation(disconnectedScreen.getNarrationMessage(), RESTORE_KICK_KEY)) {
            clearAll();
            return;
        }

        reconnecting = true;
        waitTicks = config.initialDelayTicks();
        elapsedTicks = 0;
        attempts = 0;
        MineBackup.LOGGER.info("Detected MineBackup LAN restore disconnect; auto reconnect started.");
    }

    private static void trackCurrentServer(Minecraft client) {
        ServerData current = client.getCurrentServer();
        if (current == null || !current.isLan() || current.ip == null || current.ip.isBlank()) {
            clearAll();
            return;
        }
        lastLanServer = current;
        lastLanAddress = current.ip;
        resetReconnectCounters();
    }

    private static void tickReconnect(Minecraft client, Config.ClientReconnect config) {
        elapsedTicks++;
        if (elapsedTicks >= config.maxDurationTicks()) {
            int timedOutAfter = elapsedTicks;
            clearAll();
            MineBackup.LOGGER.warn("LAN auto reconnect timed out after {} ticks.", timedOutAfter);
            return;
        }
        if (client.screen instanceof ConnectScreen) {
            return;
        }
        if (!(client.screen instanceof DisconnectedScreen)) {
            clearAll();
            return;
        }
        if (waitTicks > 0) {
            waitTicks--;
            return;
        }
        if (!attemptReconnect(client)) {
            clearAll();
            return;
        }
        attempts++;
        waitTicks = config.retryIntervalTicks();
    }

    private static boolean attemptReconnect(Minecraft client) {
        if (lastLanServer == null || lastLanAddress == null || !ServerAddress.isValidAddress(lastLanAddress)) {
            MineBackup.LOGGER.warn("LAN reconnect target is invalid.");
            return false;
        }
        try {
            ServerAddress address = ServerAddress.parseString(lastLanAddress);
            ServerData target = new ServerData(lastLanServer.name, lastLanAddress, ServerData.Type.LAN);
            target.copyFrom(lastLanServer);
            MineBackup.LOGGER.info("LAN reconnect attempt {} to {}", attempts + 1, lastLanAddress);
            ConnectScreen.startConnecting(client.screen, client, address, target, false, null);
            return true;
        } catch (RuntimeException exception) {
            MineBackup.LOGGER.warn("Failed to start LAN reconnect", exception);
            return false;
        }
    }

    private static boolean containsTranslation(Component component, String key) {
        if (component == null) {
            return false;
        }
        if (component.getContents() instanceof TranslatableContents translatable
                && key.equals(translatable.getKey())) {
            return true;
        }
        for (Component sibling : component.getSiblings()) {
            if (containsTranslation(sibling, key)) {
                return true;
            }
        }
        return false;
    }

    private static void clearAll() {
        lastLanServer = null;
        lastLanAddress = null;
        resetReconnectCounters();
    }

    private static void resetReconnectCounters() {
        reconnecting = false;
        waitTicks = 0;
        elapsedTicks = 0;
        attempts = 0;
    }
}
