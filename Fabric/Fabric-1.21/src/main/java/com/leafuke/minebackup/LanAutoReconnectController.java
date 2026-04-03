package com.leafuke.minebackup;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public final class LanAutoReconnectController {
    private static final String RESTORE_KICK_KEY = "minebackup.message.restore.kick";

    private static volatile boolean lanSessionObserved;
    private static volatile boolean reconnectScheduled;
    private static volatile boolean reconnectCauseLooksRestore;

    private static ServerInfo lastLanServerEntry;
    private static String lastLanServerIp;

    private static int reconnectWaitTicks;
    private static int reconnectElapsedTicks;
    private static int reconnectAttempts;

    private LanAutoReconnectController() {
    }

    public static void onClientTick(MinecraftClient client) {
        if (client == null) {
            return;
        }

        if (client.world != null) {
            trackLanSession(client);
            return;
        }

        if (shouldDeferToHostRejoinFlow()) {
            stopReconnect(true);
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

        if (client.getServer() != null) {
            clearDisconnectedState();
            return;
        }

        if (!(client.currentScreen instanceof DisconnectedScreen)) {
            clearDisconnectedState();
            return;
        }

        if (!lanSessionObserved || isBlank(lastLanServerIp) || lastLanServerEntry == null) {
            return;
        }

        reconnectCauseLooksRestore = isLikelyRestoreKick(client.currentScreen);
        if (!reconnectCauseLooksRestore) {
            stopReconnect(true);
            return;
        }

        reconnectScheduled = true;
        reconnectWaitTicks = Math.max(
                Config.getLanClientReconnectInitialDelayTicks(),
                Config.getLanClientReconnectIntervalTicks());
        reconnectElapsedTicks = 0;
        reconnectAttempts = 0;

        MineBackup.LOGGER.info("Detected LAN disconnect. Starting auto reconnect.");
    }

    private static void trackLanSession(MinecraftClient client) {
        ServerInfo current = client.getCurrentServerEntry();
        if (!isLanServer(current)) {
            stopReconnect(true);
            return;
        }

        String ip = readServerAddress(current);
        if (isBlank(ip)) {
            return;
        }

        lanSessionObserved = true;
        lastLanServerEntry = current;
        lastLanServerIp = ip;

        reconnectScheduled = false;
        reconnectCauseLooksRestore = false;
        reconnectWaitTicks = 0;
        reconnectElapsedTicks = 0;
        reconnectAttempts = 0;
    }

    private static void tickReconnect(MinecraftClient client) {
        if (!Config.isAutoReconnectLanClientAfterRestore()) {
            stopReconnect(true);
            return;
        }

        if (client.world != null) {
            stopReconnect(false);
            return;
        }

        if (client.currentScreen instanceof ConnectScreen) {
            return;
        }

        if (!(client.currentScreen instanceof DisconnectedScreen)) {
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

    private static boolean attemptReconnect(MinecraftClient client) {
        if (lastLanServerEntry == null || isBlank(lastLanServerIp)) {
            return false;
        }

        if (!ServerAddress.isValid(lastLanServerIp)) {
            return false;
        }

        ServerAddress address = ServerAddress.parse(lastLanServerIp);
        if (address == null) {
            return false;
        }

        MineBackup.LOGGER.info("LAN auto reconnect attempt {} to {}", reconnectAttempts + 1, lastLanServerIp);
        return invokeConnectScreen(client, address, lastLanServerEntry);
    }

    private static boolean invokeConnectScreen(MinecraftClient client, ServerAddress address, ServerInfo serverInfo) {
        for (Method method : ConnectScreen.class.getMethods()) {
            if (!Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            String methodName = method.getName();
            if (!"connect".equals(methodName) && !"startConnecting".equals(methodName)) {
                continue;
            }

            Class<?>[] paramTypes = method.getParameterTypes();
            Object[] args = new Object[paramTypes.length];
            boolean hasClient = false;
            boolean hasAddress = false;
            boolean hasServerInfo = false;

            for (int i = 0; i < paramTypes.length; i++) {
                Class<?> type = paramTypes[i];
                if (MinecraftClient.class.isAssignableFrom(type)) {
                    args[i] = client;
                    hasClient = true;
                } else if (ServerAddress.class.isAssignableFrom(type)) {
                    args[i] = address;
                    hasAddress = true;
                } else if (ServerInfo.class.isAssignableFrom(type)) {
                    args[i] = serverInfo;
                    hasServerInfo = true;
                } else if (Screen.class.isAssignableFrom(type)) {
                    args[i] = client.currentScreen;
                } else if (type == boolean.class || type == Boolean.class) {
                    args[i] = false;
                } else {
                    args[i] = null;
                }
            }

            if (!hasClient || !hasAddress || !hasServerInfo) {
                continue;
            }

            try {
                method.invoke(null, args);
                return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private static boolean isLanServer(ServerInfo serverInfo) {
        if (serverInfo == null) {
            return false;
        }

        Object type = invokeInstance(serverInfo, "getServerType");
        if (type == null) {
            type = invokeField(serverInfo, "serverType");
        }
        return type != null && "LAN".equalsIgnoreCase(String.valueOf(type));
    }

    private static String readServerAddress(ServerInfo serverInfo) {
        Object value = invokeField(serverInfo, "address");
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }

        value = invokeInstance(serverInfo, "getAddress");
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static Object invokeField(Object instance, String fieldName) {
        if (instance == null || fieldName == null) {
            return null;
        }
        try {
            Field field = instance.getClass().getField(fieldName);
            return field.get(instance);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Object invokeInstance(Object instance, String methodName) {
        if (instance == null || methodName == null) {
            return null;
        }
        try {
            Method method = instance.getClass().getMethod(methodName);
            return method.invoke(instance);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void stopReconnect(boolean clearLanSession) {
        reconnectScheduled = false;
        reconnectCauseLooksRestore = false;
        reconnectWaitTicks = 0;
        reconnectElapsedTicks = 0;
        reconnectAttempts = 0;

        if (clearLanSession) {
            clearLanSessionState();
        }
    }

    private static void clearLanSessionState() {
        lanSessionObserved = false;
        lastLanServerEntry = null;
        lastLanServerIp = null;
    }

    private static void clearDisconnectedState() {
        reconnectScheduled = false;
        reconnectCauseLooksRestore = false;
        reconnectWaitTicks = 0;
        reconnectElapsedTicks = 0;
        reconnectAttempts = 0;
    }

    private static boolean shouldDeferToHostRejoinFlow() {
        return !isBlank(MineBackupClient.getWorldToRejoin());
    }

    private static boolean isLikelyRestoreKick(Screen screen) {
        if (screen == null) {
            return false;
        }

        Object narration = invokeInstance(screen, "getNarrationMessage");
        if (narration == null) {
            narration = invokeInstance(screen, "getNarratedTitle");
        }
        return containsTranslatableKey(narration, RESTORE_KICK_KEY);
    }

    private static boolean containsTranslatableKey(Object textLike, String key) {
        if (textLike == null || key == null) {
            return false;
        }

        Object content = invokeInstance(textLike, "getContent");
        Object translatableKey = invokeInstance(content, "getKey");
        if (translatableKey instanceof String value && key.equals(value)) {
            return true;
        }

        Object siblings = invokeInstance(textLike, "getSiblings");
        if (siblings instanceof Iterable<?> iterable) {
            for (Object sibling : iterable) {
                if (containsTranslatableKey(sibling, key)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
