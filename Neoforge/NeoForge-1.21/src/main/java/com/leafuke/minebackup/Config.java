package com.leafuke.minebackup;

import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class Config {
    private static final String CONFIG_FILE = "minebackup-auto.properties";

    private static String configId;
    private static int worldIndex = -1;
    private static int internalTime = -1;

    private Config() {
    }

    public static void load() {
        Path configPath = FMLPaths.CONFIGDIR.get().resolve(CONFIG_FILE);
        if (!Files.exists(configPath)) {
            return;
        }

        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            props.load(reader);
            configId = normalizeConfigId(props.getProperty("configId"));
            worldIndex = parseInt(props.getProperty("worldIndex"), -1);
            internalTime = parseInt(props.getProperty("internalTime"), -1);
        } catch (IOException e) {
            MineBackup.LOGGER.error("Failed to load config", e);
            clearInMemory();
        }
    }

    public static void save() {
        Path configPath = FMLPaths.CONFIGDIR.get().resolve(CONFIG_FILE);
        Properties props = new Properties();
        if (configId != null) {
            props.setProperty("configId", configId);
        }
        props.setProperty("worldIndex", String.valueOf(worldIndex));
        props.setProperty("internalTime", String.valueOf(internalTime));

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

    private static void clearInMemory() {
        configId = null;
        worldIndex = -1;
        internalTime = -1;
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
}
