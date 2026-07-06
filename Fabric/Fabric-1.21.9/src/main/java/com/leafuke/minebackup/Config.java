package com.leafuke.minebackup;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class Config {
    private static final String CONFIG_FILE = "minebackup-auto.properties";

    private static final boolean AUTO_REOPEN_LAN_AFTER_RESTORE_DEFAULT = true;
    private static final int LAN_REOPEN_RETRY_COUNT_DEFAULT = 6;
    private static final int LAN_REOPEN_RETRY_INTERVAL_TICKS_DEFAULT = 40;
    private static final boolean LAN_REOPEN_ALLOW_RANDOM_PORT_FALLBACK_DEFAULT = true;

    private static final boolean AUTO_RECONNECT_LAN_CLIENT_AFTER_RESTORE_DEFAULT = true;
    private static final int LAN_CLIENT_RECONNECT_INITIAL_DELAY_TICKS_DEFAULT = 200;
    private static final int LAN_CLIENT_RECONNECT_INTERVAL_TICKS_DEFAULT = 100;
    private static final int LAN_CLIENT_RECONNECT_MAX_DURATION_TICKS_DEFAULT = 1800;
    private static final boolean ENABLE_UPDATE_CHECK_DEFAULT = true;

    private static String configId;
    private static int worldIndex = -1;
    private static int internalTime = -1;
    private static boolean autoReopenLanAfterRestore = AUTO_REOPEN_LAN_AFTER_RESTORE_DEFAULT;
    private static int lanReopenRetryCount = LAN_REOPEN_RETRY_COUNT_DEFAULT;
    private static int lanReopenRetryIntervalTicks = LAN_REOPEN_RETRY_INTERVAL_TICKS_DEFAULT;
    private static boolean lanReopenAllowRandomPortFallback = LAN_REOPEN_ALLOW_RANDOM_PORT_FALLBACK_DEFAULT;
    private static boolean autoReconnectLanClientAfterRestore = AUTO_RECONNECT_LAN_CLIENT_AFTER_RESTORE_DEFAULT;
    private static int lanClientReconnectInitialDelayTicks = LAN_CLIENT_RECONNECT_INITIAL_DELAY_TICKS_DEFAULT;
    private static int lanClientReconnectIntervalTicks = LAN_CLIENT_RECONNECT_INTERVAL_TICKS_DEFAULT;
    private static int lanClientReconnectMaxDurationTicks = LAN_CLIENT_RECONNECT_MAX_DURATION_TICKS_DEFAULT;
    private static boolean enableUpdateCheck = ENABLE_UPDATE_CHECK_DEFAULT;

    private Config() {
    }

    public static void load() {
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE);
        if (!Files.exists(configPath)) {
            clearInMemory();
            save();
            return;
        }

        Properties props = new Properties();
        boolean shouldSave = false;
        try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            props.load(reader);
            configId = normalizeConfigId(props.getProperty("configId"));
            worldIndex = parseInt(props.getProperty("worldIndex"), -1);
            internalTime = parseInt(props.getProperty("internalTime"), -1);
            autoReopenLanAfterRestore = parseBoolean(props.getProperty("autoReopenLanAfterRestore"),
                AUTO_REOPEN_LAN_AFTER_RESTORE_DEFAULT);
            lanReopenRetryCount = clamp(parseInt(props.getProperty("lanReopenRetryCount"),
                LAN_REOPEN_RETRY_COUNT_DEFAULT), 1, 30);
            lanReopenRetryIntervalTicks = clamp(parseInt(props.getProperty("lanReopenRetryIntervalTicks"),
                LAN_REOPEN_RETRY_INTERVAL_TICKS_DEFAULT), 10, 200);
            lanReopenAllowRandomPortFallback = parseBoolean(props.getProperty("lanReopenAllowRandomPortFallback"),
                LAN_REOPEN_ALLOW_RANDOM_PORT_FALLBACK_DEFAULT);
            autoReconnectLanClientAfterRestore = parseBoolean(props.getProperty("autoReconnectLanClientAfterRestore"),
                AUTO_RECONNECT_LAN_CLIENT_AFTER_RESTORE_DEFAULT);
            lanClientReconnectInitialDelayTicks = clamp(parseInt(props.getProperty("lanClientReconnectInitialDelayTicks"),
                LAN_CLIENT_RECONNECT_INITIAL_DELAY_TICKS_DEFAULT), 40, 600);
            lanClientReconnectIntervalTicks = clamp(parseInt(props.getProperty("lanClientReconnectIntervalTicks"),
                LAN_CLIENT_RECONNECT_INTERVAL_TICKS_DEFAULT), 20, 200);
            lanClientReconnectMaxDurationTicks = clamp(parseInt(props.getProperty("lanClientReconnectMaxDurationTicks"),
                LAN_CLIENT_RECONNECT_MAX_DURATION_TICKS_DEFAULT), 200, 7200);
            enableUpdateCheck = parseBoolean(props.getProperty("enableUpdateCheck"),
                ENABLE_UPDATE_CHECK_DEFAULT);
            shouldSave = hasMissingRequiredKeys(props);
        } catch (IOException e) {
            MineBackup.LOGGER.error("Failed to load config", e);
            clearInMemory();
        }

        if (shouldSave) {
            save();
        }
    }

    public static void save() {
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE);
        Properties props = new Properties();
        if (configId != null) {
            props.setProperty("configId", configId);
        }
        props.setProperty("worldIndex", String.valueOf(worldIndex));
        props.setProperty("internalTime", String.valueOf(internalTime));
        props.setProperty("autoReopenLanAfterRestore", String.valueOf(autoReopenLanAfterRestore));
        props.setProperty("lanReopenRetryCount", String.valueOf(lanReopenRetryCount));
        props.setProperty("lanReopenRetryIntervalTicks", String.valueOf(lanReopenRetryIntervalTicks));
        props.setProperty("lanReopenAllowRandomPortFallback", String.valueOf(lanReopenAllowRandomPortFallback));
        props.setProperty("autoReconnectLanClientAfterRestore", String.valueOf(autoReconnectLanClientAfterRestore));
        props.setProperty("lanClientReconnectInitialDelayTicks", String.valueOf(lanClientReconnectInitialDelayTicks));
        props.setProperty("lanClientReconnectIntervalTicks", String.valueOf(lanClientReconnectIntervalTicks));
        props.setProperty("lanClientReconnectMaxDurationTicks", String.valueOf(lanClientReconnectMaxDurationTicks));
        props.setProperty("enableUpdateCheck", String.valueOf(enableUpdateCheck));

        try (Writer writer = Files.newBufferedWriter(configPath, StandardCharsets.UTF_8)) {
            props.store(writer, "MineBackup Auto Config");
        } catch (IOException e) {
            MineBackup.LOGGER.error("Failed to save config", e);
        }
    }

    public static void setAutoBackup(String cid, int wid, int time) {
        configId = normalizeConfigId(cid);
        worldIndex = wid;
        internalTime = time;
        save();
    }

    public static void clearAutoBackup() {
        clearInMemory();
        save();
    }

    public static boolean hasAutoBackup() {
        return configId != null && worldIndex >= 0 && internalTime >= 0;
    }

    public static String getConfigId() {
        return configId;
    }

    public static int getWorldIndex() {
        return worldIndex;
    }

    public static int getInternalTime() {
        return internalTime;
    }

    public static boolean isAutoReopenLanAfterRestore() {
        return autoReopenLanAfterRestore;
    }

    public static int getLanReopenRetryCount() {
        return lanReopenRetryCount;
    }

    public static int getLanReopenRetryIntervalTicks() {
        return lanReopenRetryIntervalTicks;
    }

    public static boolean isLanReopenAllowRandomPortFallback() {
        return lanReopenAllowRandomPortFallback;
    }

    public static boolean isAutoReconnectLanClientAfterRestore() {
        return autoReconnectLanClientAfterRestore;
    }

    public static int getLanClientReconnectInitialDelayTicks() {
        return lanClientReconnectInitialDelayTicks;
    }

    public static int getLanClientReconnectIntervalTicks() {
        return lanClientReconnectIntervalTicks;
    }

    public static int getLanClientReconnectMaxDurationTicks() {
        return lanClientReconnectMaxDurationTicks;
    }

    public static boolean isUpdateCheckEnabled() {
        return enableUpdateCheck;
    }

    private static void clearInMemory() {
        configId = null;
        worldIndex = -1;
        internalTime = -1;
        autoReopenLanAfterRestore = AUTO_REOPEN_LAN_AFTER_RESTORE_DEFAULT;
        lanReopenRetryCount = LAN_REOPEN_RETRY_COUNT_DEFAULT;
        lanReopenRetryIntervalTicks = LAN_REOPEN_RETRY_INTERVAL_TICKS_DEFAULT;
        lanReopenAllowRandomPortFallback = LAN_REOPEN_ALLOW_RANDOM_PORT_FALLBACK_DEFAULT;
        autoReconnectLanClientAfterRestore = AUTO_RECONNECT_LAN_CLIENT_AFTER_RESTORE_DEFAULT;
        lanClientReconnectInitialDelayTicks = LAN_CLIENT_RECONNECT_INITIAL_DELAY_TICKS_DEFAULT;
        lanClientReconnectIntervalTicks = LAN_CLIENT_RECONNECT_INTERVAL_TICKS_DEFAULT;
        lanClientReconnectMaxDurationTicks = LAN_CLIENT_RECONNECT_MAX_DURATION_TICKS_DEFAULT;
        enableUpdateCheck = ENABLE_UPDATE_CHECK_DEFAULT;
    }

    private static boolean hasMissingRequiredKeys(Properties props) {
        return !props.containsKey("worldIndex")
                || !props.containsKey("internalTime")
                || !props.containsKey("autoReopenLanAfterRestore")
                || !props.containsKey("lanReopenRetryCount")
                || !props.containsKey("lanReopenRetryIntervalTicks")
                || !props.containsKey("lanReopenAllowRandomPortFallback")
                || !props.containsKey("autoReconnectLanClientAfterRestore")
                || !props.containsKey("lanClientReconnectInitialDelayTicks")
                || !props.containsKey("lanClientReconnectIntervalTicks")
                || !props.containsKey("lanClientReconnectMaxDurationTicks")
                || !props.containsKey("enableUpdateCheck");
    }

    private static String normalizeConfigId(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static boolean parseBoolean(String value, boolean fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Boolean.parseBoolean(value.trim());
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
