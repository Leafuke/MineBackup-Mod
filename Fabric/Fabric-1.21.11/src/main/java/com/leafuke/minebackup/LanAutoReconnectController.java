package com.leafuke.minebackup;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

public final class LanAutoReconnectController {
    private static final String RESTORE_KICK_KEY = "minebackup.message.restore.kick";

    private static volatile boolean lanSessionObserved;
    private static volatile boolean reconnectScheduled;
    private static volatile boolean reconnectCauseLooksRestore;

    private static String lastLanServerIp;
    private static String lastLanServerName;

    private static int reconnectWaitTicks;
    private static int reconnectElapsedTicks;
    private static int reconnectAttempts;

    private LanAutoReconnectController() {
    }

    public static void onClientTick(Minecraft client) {
        if (client == null) {
            return;
        }

        if (client.level != null) {
            trackLanSession(client);
            return;
        }

        if (reconnectScheduled) {
            tickReconnect(client);
            return;
        }

        if (!Config.isAutoReconnectLanClientAfterRestore()) {
            clearDisconnectedState();
            return;
        }

        if (client.getSingleplayerServer() != null) {
            clearDisconnectedState();
            return;
        }

        if (!(client.screen instanceof DisconnectedScreen)) {
            clearDisconnectedState();
            return;
        }

        if (!lanSessionObserved || isBlank(lastLanServerIp)) {
            return;
        }

        reconnectCauseLooksRestore = isLikelyRestoreKick(client.screen);
        reconnectScheduled = true;
        reconnectWaitTicks = Math.max(
            Config.getLanClientReconnectInitialDelayTicks(),
            Config.getLanClientReconnectIntervalTicks());
        reconnectElapsedTicks = 0;
        reconnectAttempts = 0;

        MineBackup.LOGGER.info(
                "Detected LAN disconnect{}. Starting auto reconnect.",
                reconnectCauseLooksRestore ? " during restore" : "");
    }

    private static void trackLanSession(Minecraft client) {
        ServerData current = client.getCurrentServer();
        if (current == null || !current.isLan()) {
            return;
        }

        lanSessionObserved = true;
        lastLanServerIp = current.ip;
        lastLanServerName = current.name;

        reconnectScheduled = false;
        reconnectCauseLooksRestore = false;
        reconnectWaitTicks = 0;
        reconnectElapsedTicks = 0;
        reconnectAttempts = 0;
    }

    private static void tickReconnect(Minecraft client) {
        if (!Config.isAutoReconnectLanClientAfterRestore()) {
            stopReconnect(true);
            return;
        }

        if (client.level != null) {
            stopReconnect(false);
            return;
        }

        if (client.screen instanceof ConnectScreen) {
            return;
        }

        if (!(client.screen instanceof DisconnectedScreen)) {
            stopReconnect(true);
            return;
        }

        reconnectElapsedTicks++;
        if (reconnectElapsedTicks >= Config.getLanClientReconnectMaxDurationTicks()) {
            stopReconnect(true);
            MineBackup.LOGGER.warn("LAN auto reconnect timed out after {} ticks.", reconnectElapsedTicks);
            return;
        }

        if (reconnectWaitTicks > 0) {
            reconnectWaitTicks--;
            return;
        }

        if (!attemptReconnect(client)) {
            stopReconnect(true);
            MineBackup.LOGGER.warn("LAN auto reconnect aborted due to invalid target address.");
            return;
        }

        reconnectAttempts++;
        reconnectWaitTicks = Config.getLanClientReconnectIntervalTicks();
    }

    private static boolean attemptReconnect(Minecraft client) {
        if (isBlank(lastLanServerIp)) {
            return false;
        }

        if (!ServerAddress.isValidAddress(lastLanServerIp)) {
            return false;
        }

        ServerAddress address = ServerAddress.parseString(lastLanServerIp);
        if (address == null) {
            return false;
        }

        String serverName = isBlank(lastLanServerName) ? "LAN" : lastLanServerName;
        ServerData reconnectTarget = new ServerData(serverName, lastLanServerIp, ServerData.Type.LAN);

        MineBackup.LOGGER.info("LAN auto reconnect attempt {} to {}", reconnectAttempts + 1, lastLanServerIp);
        ConnectScreen.startConnecting(client.screen, client, address, reconnectTarget, false, null);
        return true;
    }

    private static void stopReconnect(boolean clearLanSession) {
        reconnectScheduled = false;
        reconnectCauseLooksRestore = false;
        reconnectWaitTicks = 0;
        reconnectElapsedTicks = 0;
        reconnectAttempts = 0;

        if (clearLanSession) {
            lanSessionObserved = false;
        }
    }

    private static void clearDisconnectedState() {
        reconnectScheduled = false;
        reconnectCauseLooksRestore = false;
        reconnectWaitTicks = 0;
        reconnectElapsedTicks = 0;
        reconnectAttempts = 0;
    }

    private static boolean isLikelyRestoreKick(Screen screen) {
        if (screen == null) {
            return false;
        }
        return containsTranslatableKey(screen.getNarrationMessage(), RESTORE_KICK_KEY);
    }

    private static boolean containsTranslatableKey(Component component, String key) {
        if (component == null || key == null) {
            return false;
        }

        if (component.getContents() instanceof TranslatableContents translatable && key.equals(translatable.getKey())) {
            return true;
        }

        for (Component sibling : component.getSiblings()) {
            if (containsTranslatableKey(sibling, key)) {
                return true;
            }
        }

        return false;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
