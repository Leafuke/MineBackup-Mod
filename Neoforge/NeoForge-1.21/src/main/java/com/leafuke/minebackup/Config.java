package com.leafuke.minebackup;

import net.neoforged.fml.loading.FMLPaths;
import java.io.*;
import java.nio.file.Path;
import java.util.Properties;

public class Config {
    private static final String CONFIG_FILE = "minebackup-auto.properties";
    private static int configId = -1;
    private static int worldIndex = -1;
    private static int internalTime = -1;

    public static void load() {
        Path configPath = FMLPaths.CONFIGDIR.get().resolve(CONFIG_FILE);
        File file = configPath.toFile();
        if (!file.exists()) return;

        try (FileInputStream fis = new FileInputStream(file)) {
            Properties props = new Properties();
            props.load(fis);
            configId = Integer.parseInt(props.getProperty("configId", "-1"));
            worldIndex = Integer.parseInt(props.getProperty("worldIndex", "-1"));
            internalTime = Integer.parseInt(props.getProperty("internalTime", "-1"));
        } catch (IOException | NumberFormatException e) {
            MineBackup.LOGGER.error("Failed to load config", e);
        }
    }

    public static void save() {
        Path configPath = FMLPaths.CONFIGDIR.get().resolve(CONFIG_FILE);
        try (FileOutputStream fos = new FileOutputStream(configPath.toFile())) {
            Properties props = new Properties();
            props.setProperty("configId", String.valueOf(configId));
            props.setProperty("worldIndex", String.valueOf(worldIndex));
            props.setProperty("internalTime", String.valueOf(internalTime));
            props.store(fos, "MineBackup Auto Config");
        } catch (IOException e) {
            MineBackup.LOGGER.error("Failed to save config", e);
        }
    }

    public static void setAutoBackup(int cid, int wid, int time) {
        configId = cid;
        worldIndex = wid;
        internalTime = time;
        save();
    }

    public static void clearAutoBackup() {
        configId = -1;
        worldIndex = -1;
        internalTime = -1;
        save();
    }

    public static boolean hasAutoBackup() {
        return configId != -1 && worldIndex != -1 && internalTime != -1;
    }

    public static int getConfigId() { return configId; }
    public static int getWorldIndex() { return worldIndex; }
    public static int getInternalTime() { return internalTime; }
}

