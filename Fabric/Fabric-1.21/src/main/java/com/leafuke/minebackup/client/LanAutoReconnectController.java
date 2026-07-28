package com.leafuke.minebackup.client;

import com.leafuke.minebackup.MineBackup;
import com.leafuke.minebackup.config.Config;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;

public final class LanAutoReconnectController {
    private static final String RESTORE_KICK_KEY = "minebackup.message.restore.kick";

    private static ServerInfo lastLanServer;
    private static String lastLanAddress;
    private static boolean reconnecting;
    private static int waitTicks;
    private static int elapsedTicks;
    private static int attempts;

    private LanAutoReconnectController() {
    }

    public static void onClientTick(MinecraftClient client) {
        if (client.world != null) {
            trackCurrentServer(client);
            return;
        }

        Config.ClientReconnect config = Config.get().clientReconnect();
        if (!config.enabled()) {
            clearAll();
            return;
        }
        if (client.getServer() != null) {
            clearAll();
            return;
        }
        if (reconnecting) {
            tickReconnect(client, config);
            return;
        }
        // // // if (client.currentScreen instanceof ConnectScreen) // ConnectScreen not available in Yarn 1.21
 if (false) // ConnectScreen not available in Yarn 1.21
 if (false) // ConnectScreen not available in Yarn 1.21

        if (false) {
            return;
        }
        if (!(client.currentScreen instanceof DisconnectedScreen disconnectedScreen)) {
            clearAll();
            return;
        }
        if (lastLanServer == null || lastLanAddress == null) {
            return;
        }
        if (!containsTranslation(disconnectedScreen.getNarratedTitle(), RESTORE_KICK_KEY)) {
            clearAll();
            return;
        }

        reconnecting = true;
        waitTicks = config.initialDelayTicks();
        elapsedTicks = 0;
        attempts = 0;
        MineBackup.LOGGER.info("Detected MineBackup LAN restore disconnect; auto reconnect started.");
    }

    private static void trackCurrentServer(MinecraftClient client) {
        ServerInfo current = client.getCurrentServerEntry();
        if (current == null || !current.isLocal() || current.address == null || current.address.isBlank()) {
            clearAll();
            return;
        }
        lastLanServer = current;
        lastLanAddress = current.address;
        resetReconnectCounters();
    }

    private static void tickReconnect(MinecraftClient client, Config.ClientReconnect config) {
        elapsedTicks++;
        if (elapsedTicks >= config.maxDurationTicks()) {
            int timedOutAfter = elapsedTicks;
            clearAll();
            MineBackup.LOGGER.warn("LAN auto reconnect timed out after {} ticks.", timedOutAfter);
            return;
        }
        // // // if (client.currentScreen instanceof ConnectScreen) // ConnectScreen not available in Yarn 1.21
 if (false) // ConnectScreen not available in Yarn 1.21
 if (false) // ConnectScreen not available in Yarn 1.21

        if (false) {
            return;
        }
        if (!(client.currentScreen instanceof DisconnectedScreen)) {
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

    private static boolean attemptReconnect(MinecraftClient client) {
        if (lastLanServer == null || lastLanAddress == null || !ServerAddress.isValid(lastLanAddress)) {
            MineBackup.LOGGER.warn("LAN reconnect target is invalid.");
            return false;
        }
        try {
            ServerAddress address = ServerAddress.parse(lastLanAddress);
            ServerInfo target = new ServerInfo(lastLanServer.name, lastLanAddress, ServerInfo.ServerType.LAN);
            target.copyFrom(lastLanServer);
            MineBackup.LOGGER.info("LAN reconnect attempt {} to {}", attempts + 1, lastLanAddress);
            // // // ConnectScreen.connect(client.currentScreen, client, address, target, false, null); // ConnectScreen not available in Yarn 1.21 // ConnectScreen not available in Yarn 1.21 // ConnectScreen not available in Yarn 1.21
            return true;
        } catch (RuntimeException exception) {
            MineBackup.LOGGER.warn("Failed to start LAN reconnect", exception);
            return false;
        }
    }

    private static boolean containsTranslation(Text component, String key) {
        if (component == null) {
            return false;
        }
        if (component.getContent() instanceof TranslatableTextContent translatable
                && key.equals(translatable.getKey())) {
            return true;
        }
        for (Text sibling : component.getSiblings()) {
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
