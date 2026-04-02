package com.leafuke.minebackup;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public final class LanAutoReconnectController {
    private static volatile boolean lanSessionObserved;
    private static volatile boolean reconnectScheduled;

    private static ServerData lastLanServerData;
    private static String lastLanServerIp;

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

        if (!lanSessionObserved || isBlank(lastLanServerIp) || lastLanServerData == null) {
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

    private static void trackLanSession(Minecraft client) {
        ServerData current = client.getCurrentServer();
        if (!isLanServer(current)) {
            return;
        }

        String ip = readServerIp(current);
        if (isBlank(ip)) {
            return;
        }

        lanSessionObserved = true;
        lastLanServerData = current;
        lastLanServerIp = ip;

        reconnectScheduled = false;
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
        if (lastLanServerData == null || isBlank(lastLanServerIp)) {
            return false;
        }

        ServerAddress address = parseServerAddress(lastLanServerIp);
        if (address == null) {
            return false;
        }

        MineBackup.LOGGER.info("LAN auto reconnect attempt {} to {}", reconnectAttempts + 1, lastLanServerIp);
        return invokeConnectScreen(client, address, lastLanServerData);
    }

    private static ServerAddress parseServerAddress(String rawAddress) {
        Object parsed = invokeStatic(ServerAddress.class, "parseString", rawAddress);
        if (!(parsed instanceof ServerAddress)) {
            parsed = invokeStatic(ServerAddress.class, "parse", rawAddress);
        }
        return parsed instanceof ServerAddress address ? address : null;
    }

    private static boolean invokeConnectScreen(Minecraft client, ServerAddress address, ServerData serverData) {
        for (Method method : ConnectScreen.class.getMethods()) {
            if (!Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            String methodName = method.getName();
            if (!"startConnecting".equals(methodName) && !"connect".equals(methodName)) {
                continue;
            }

            Class<?>[] paramTypes = method.getParameterTypes();
            Object[] args = new Object[paramTypes.length];
            boolean hasClient = false;
            boolean hasAddress = false;
            boolean hasServerData = false;

            for (int i = 0; i < paramTypes.length; i++) {
                Class<?> type = paramTypes[i];
                if (Minecraft.class.isAssignableFrom(type)) {
                    args[i] = client;
                    hasClient = true;
                } else if (ServerAddress.class.isAssignableFrom(type)) {
                    args[i] = address;
                    hasAddress = true;
                } else if (ServerData.class.isAssignableFrom(type)) {
                    args[i] = serverData;
                    hasServerData = true;
                } else if (Screen.class.isAssignableFrom(type)) {
                    args[i] = client.screen;
                } else if (type == boolean.class || type == Boolean.class) {
                    args[i] = false;
                } else {
                    args[i] = null;
                }
            }

            if (!hasClient || !hasAddress || !hasServerData) {
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

    private static boolean isLanServer(ServerData serverData) {
        if (serverData == null) {
            return false;
        }

        Object result = invokeInstance(serverData, "isLan");
        if (result instanceof Boolean bool) {
            return bool;
        }

        Object type = invokeInstance(serverData, "getServerType");
        if (type == null) {
            type = invokeField(serverData, "type");
        }
        return type != null && "LAN".equalsIgnoreCase(String.valueOf(type));
    }

    private static String readServerIp(ServerData serverData) {
        Object value = invokeField(serverData, "ip");
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }

        value = invokeInstance(serverData, "getIp");
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }

        value = invokeInstance(serverData, "getAddress");
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

    private static Object invokeStatic(Class<?> owner, String methodName, String value) {
        if (owner == null || methodName == null) {
            return null;
        }
        try {
            Method method = owner.getMethod(methodName, String.class);
            return method.invoke(null, value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void stopReconnect(boolean clearLanSession) {
        reconnectScheduled = false;
        reconnectWaitTicks = 0;
        reconnectElapsedTicks = 0;
        reconnectAttempts = 0;

        if (clearLanSession) {
            lanSessionObserved = false;
            lastLanServerData = null;
            lastLanServerIp = null;
        }
    }

    private static void clearDisconnectedState() {
        reconnectScheduled = false;
        reconnectWaitTicks = 0;
        reconnectElapsedTicks = 0;
        reconnectAttempts = 0;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
