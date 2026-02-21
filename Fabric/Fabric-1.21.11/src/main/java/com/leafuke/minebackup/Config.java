package com.leafuke.minebackup;

import net.fabricmc.loader.api.FabricLoader;
import java.io.*;
import java.nio.file.Path;
import java.util.Properties;

/**
 * MineBackup 配置管理类
 * 用于存储和加载自动备份配置
 */
public class Config {
    private static final String CONFIG_FILE = "minebackup-auto.properties";
    private static int configId = -1;
    private static int worldIndex = -1;
    private static int internalTime = -1;

    /**
     * 从配置文件加载设置
     */
    public static void load() {
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE);
        File file = configPath.toFile();
        if (!file.exists()) return;

        try (FileInputStream fis = new FileInputStream(file)) {
            Properties props = new Properties();
            props.load(fis);
            configId = Integer.parseInt(props.getProperty("configId", "-1"));
            worldIndex = Integer.parseInt(props.getProperty("worldIndex", "-1"));
            internalTime = Integer.parseInt(props.getProperty("internalTime", "-1"));
            MineBackup.LOGGER.info("[MineBackup] 配置加载成功: configId={}, worldIndex={}, internalTime={}",
                configId, worldIndex, internalTime);
        } catch (IOException | NumberFormatException e) {
            MineBackup.LOGGER.error("[MineBackup] 加载配置失败", e);
        }
    }

    /**
     * 保存配置到文件
     */
    public static void save() {
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE);
        try (FileOutputStream fos = new FileOutputStream(configPath.toFile())) {
            Properties props = new Properties();
            props.setProperty("configId", String.valueOf(configId));
            props.setProperty("worldIndex", String.valueOf(worldIndex));
            props.setProperty("internalTime", String.valueOf(internalTime));
            props.store(fos, "MineBackup Auto Config");
            MineBackup.LOGGER.info("[MineBackup] 配置保存成功");
        } catch (IOException e) {
            MineBackup.LOGGER.error("[MineBackup] 保存配置失败", e);
        }
    }

    /**
     * 设置自动备份参数
     * @param cid 配置ID
     * @param wid 世界索引
     * @param time 备份间隔（秒）
     */
    public static void setAutoBackup(int cid, int wid, int time) {
        configId = cid;
        worldIndex = wid;
        internalTime = time;
        save();
    }

    /**
     * 清除自动备份配置
     */
    public static void clearAutoBackup() {
        configId = -1;
        worldIndex = -1;
        internalTime = -1;
        save();
    }

    /**
     * 检查是否配置了自动备份
     * @return 是否配置了有效的自动备份参数
     */
    public static boolean hasAutoBackup() {
        return configId != -1 && worldIndex != -1 && internalTime != -1;
    }

    public static int getConfigId() { return configId; }
    public static int getWorldIndex() { return worldIndex; }
    public static int getInternalTime() { return internalTime; }
}

