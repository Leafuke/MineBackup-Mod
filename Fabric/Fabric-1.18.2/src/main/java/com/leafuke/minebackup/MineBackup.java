package com.leafuke.minebackup;

import com.leafuke.minebackup.knotlink.SignalSubscriber;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.network.MessageType; // <-- 关键导入
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

public class MineBackup implements ModInitializer {

    public static final String MOD_ID = "minebackup";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static SignalSubscriber knotLinkSubscriber;
    private static volatile MinecraftServer serverInstance;

    public static final String BROADCAST_APP_ID = "0x00000020";
    public static final String BROADCAST_SIGNAL_ID = "0x00000020";

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing MineBackup for Fabric 1.18.2...");
        registerServerLifecycleEvents();
        registerCommands();
    }



    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> {
            Command.register(dispatcher);
        });
    }

    private void registerServerLifecycleEvents() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            LOGGER.info("MineBackup Mod: Server is starting, initializing KnotLink Subscriber...");
            serverInstance = server;
            knotLinkSubscriber = new SignalSubscriber(BROADCAST_APP_ID, BROADCAST_SIGNAL_ID);
            knotLinkSubscriber.setSignalListener(this::handleBroadcastEvent);
            new Thread(knotLinkSubscriber::start).start();
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            LOGGER.info("MineBackup Mod: Server is stopping, shutting down KnotLink Subscriber...");
            if (knotLinkSubscriber != null) {
                knotLinkSubscriber.stop();
            }
            serverInstance = null;
        });
    }

    // 修正后的广播方法
    private void broadcastMessage(Text message) {
        if (serverInstance != null) {
            // 在 1.18.2, 正确的签名是 broadcast(Text, MessageType, UUID)
            serverInstance.getPlayerManager().broadcast(message, MessageType.SYSTEM, Util.NIL_UUID);
        }
    }


    private Map<String, String> parsePayload(String payload) {
        Map<String, String> dataMap = new HashMap<>();
        if (payload == null || payload.isEmpty()) return dataMap;
        for (String pair : payload.split(";")) {
            String[] keyValue = pair.split("=", 2);
            if (keyValue.length == 2) {
                dataMap.put(keyValue[0].trim(), keyValue[1].trim());
            }
        }
        return dataMap;
    }

    private void handleBroadcastEvent(String payload) {
        if (serverInstance == null) return;

        if ("minebackup save".equals(payload)) {
            serverInstance.execute(() -> {
                LOGGER.info("Received 'minebackup save' command, executing immediate world save.");
                broadcastMessage(new LiteralText("§6[MineBackup] §e正在执行远程保存指令..."));
                boolean allLevelsSaved = serverInstance.saveAll(true, true, true);
                if (allLevelsSaved) {
                    broadcastMessage(new LiteralText("§a[MineBackup] §e远程保存完成！"));
                } else {
                    broadcastMessage(new LiteralText("§c[MineBackup] §e远程保存失败！"));
                }
            });
            return;
        }

        LOGGER.info("Received MineBackup broadcast event: {}", payload);
        Map<String, String> eventData = parsePayload(payload);
        String eventType = eventData.get("event");
        if (eventType == null) return;

        final Text message;
        switch (eventType) {
            case "backup_started":
                message = new LiteralText(String.format("§6[MineBackup] §e世界 '%s' 的备份任务已开始...", eventData.getOrDefault("world", "未知世界")));
                break;
            case "restore_started":
                message = new LiteralText(String.format("§6[MineBackup] §e世界 '%s' 的还原任务已开始...", eventData.getOrDefault("world", "未知世界")));
                break;
            case "backup_success":
                message = new LiteralText(String.format("§a[MineBackup] §2备份成功! §e世界 '%s' §a已保存为 §f%s", eventData.getOrDefault("world", "未知世界"), eventData.getOrDefault("file", "未知文件")));
                break;
            case "backup_failed":
                message = new LiteralText(String.format("§c[MineBackup] §4备份失败! §e世界 '%s'. §c原因: %s", eventData.getOrDefault("world", "未知世界"), eventData.getOrDefault("error", "未知错误")));
                break;
            case "game_session_end":
                message = new LiteralText(String.format("§7[MineBackup] 游戏会话结束: %s. 后台可能已触发退出时备份。", eventData.getOrDefault("world", "未知世界")));
                break;
            case "auto_backup_started":
                message = new LiteralText(String.format("§6[MineBackup] §e世界 '%s' 的自动备份任务已开始...", eventData.getOrDefault("world", "未知世界")));
                break;
            default:
                message = null;
        }

        if ("pre_hot_backup".equals(eventType)) {
            serverInstance.execute(() -> {
                LOGGER.info("Executing immediate save for pre_hot_backup event.");
                String worldName = eventData.getOrDefault("world", "未知世界");
                broadcastMessage(new LiteralText(String.format("§6[MineBackup] §e收到热备份请求，正在为世界 '%s' 保存最新数据...", worldName)));
                boolean allSaved = serverInstance.saveAll(true, true, true);
                if (!allSaved) {
                    LOGGER.warn("One or more levels failed to save during pre_hot_backup for world: {}", worldName);
                    broadcastMessage(new LiteralText(String.format("§c[MineBackup] §4警告: 世界 '%s' 的部分数据保存失败，热备份可能不完整！", worldName)));
                }
                LOGGER.info("World saved successfully for hot backup.");
                broadcastMessage(new LiteralText("§a[MineBackup] §e世界保存完毕，备份程序已开始工作。"));
            });
        } else if ("game_session_start".equals(eventType)) {
            LOGGER.info("MineBackup detected game session start for world: {}", eventData.getOrDefault("world", "未知"));
        }

        if (message != null) {
            serverInstance.execute(() -> broadcastMessage(message));
        }
    }
}