package com.leafuke.minebackup;

import com.leafuke.minebackup.knotlink.SignalSubscriber;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;

@Mod(MineBackup.MODID)
public class MineBackup {
    public static final String MODID = "minebackup";
    public static final Logger LOGGER = LogUtils.getLogger();

    // --- 从旧代码迁移过来的核心变量 ---
    private static SignalSubscriber knotLinkSubscriber;
    private static volatile MinecraftServer serverInstance; // 使用 volatile 增强线程安全

    // KnotLink 通信ID
    public static final String BROADCAST_APP_ID = "0x00000020";
    public static final String BROADCAST_SIGNAL_ID = "0x00000020";

    public MineBackup(IEventBus modEventBus) {
        // 注册自身以监听 NeoForge 事件总线上的事件 (如服务器启动、命令注册)
        NeoForge.EVENT_BUS.register(this);
    }

    // --- 从旧代码迁移过来的核心方法 ---

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
                serverInstance.getPlayerList().broadcastSystemMessage(Component.translatable("minebackup.message.remote_save.start"), false);
                boolean allLevelsSaved = serverInstance.saveAllChunks(true, true, true);
                if (allLevelsSaved) {
                    serverInstance.getPlayerList().broadcastSystemMessage(Component.translatable("minebackup.message.remote_save.success"), false);
                } else {
                    serverInstance.getPlayerList().broadcastSystemMessage(Component.translatable("minebackup.message.remote_save.fail"), false);
                }
            });
            return;
        }

        LOGGER.info("Received MineBackup broadcast event: {}", payload);
        Map<String, String> eventData = parsePayload(payload);
        String eventType = eventData.get("event");
        if (eventType == null) return;

        final Component message = switch (eventType) {
            case "backup_started" -> Component.translatable("minebackup.broadcast.backup.started", eventData.getOrDefault("world", "unknown"));
            case "restore_started" -> Component.translatable("minebackup.broadcast.restore.started", eventData.getOrDefault("world", "unknown"));
            case "backup_success" -> Component.translatable("minebackup.broadcast.backup.success",
                    eventData.getOrDefault("world", "unknown"),
                    eventData.getOrDefault("file", "unknown"));
            case "backup_failed" -> Component.translatable("minebackup.broadcast.backup.failed",
                    eventData.getOrDefault("world", "unknown"),
                    eventData.getOrDefault("error", "unknown"));
            case "game_session_end" -> Component.translatable("minebackup.broadcast.session.end",
                    eventData.getOrDefault("world", "unknown"));
            case "auto_backup_started" -> Component.translatable("minebackup.broadcast.auto_backup.started",
                    eventData.getOrDefault("world", "unknown"));
            default -> null;
        };

        if ("pre_hot_backup".equals(eventType)) {
            serverInstance.execute(() -> {
                LOGGER.info("Executing immediate save for pre_hot_backup event.");
                String worldName = eventData.getOrDefault("world", "unknown");
                serverInstance.getPlayerList().broadcastSystemMessage(
                        Component.translatable("minebackup.broadcast.hot_backup.request", worldName), false);
                boolean allSaved = serverInstance.saveAllChunks(true, true, true);
                if (!allSaved) {
                    LOGGER.warn("One or more levels failed to save during pre_hot_backup for world: {}", worldName);
                    serverInstance.getPlayerList().broadcastSystemMessage(
                            Component.translatable("minebackup.broadcast.hot_backup.warn", worldName), false);
                }
                LOGGER.info("World saved successfully for hot backup.");
                serverInstance.getPlayerList().broadcastSystemMessage(
                        Component.translatable("minebackup.broadcast.hot_backup.complete"), false);
            });
        } else if ("game_session_start".equals(eventType)) {
            LOGGER.info("MineBackup detected game session start for world: {}", eventData.getOrDefault("world", "unknown"));
        }

        if (message != null) {
            serverInstance.execute(() -> serverInstance.getPlayerList().broadcastSystemMessage(message, false));
        }
    }

    // --- 事件监听器 ---

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("MineBackup Mod: Server is starting, initializing KnotLink Subscriber...");
        serverInstance = event.getServer();
        knotLinkSubscriber = new SignalSubscriber(BROADCAST_APP_ID, BROADCAST_SIGNAL_ID);
        knotLinkSubscriber.setSignalListener(this::handleBroadcastEvent);
        new Thread(knotLinkSubscriber::start).start();
    }

    // 添加服务器停止事件，用于清理资源
    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        LOGGER.info("MineBackup Mod: Server is stopping, shutting down KnotLink Subscriber...");
        if (knotLinkSubscriber != null) {
            knotLinkSubscriber.stop();
        }
        serverInstance = null;
    }

    // 添加命令注册事件
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        Command.register(event.getDispatcher());
    }
}
