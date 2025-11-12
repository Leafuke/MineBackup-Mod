package com.leafuke.minebackup;

import com.leafuke.minebackup.knotlink.SignalSubscriber;
import net.minecraft.Util;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

// 使用 @Mod 注解，并将你的 Mod ID 作为参数
@Mod(MineBackup.MOD_ID)
public class MineBackup {

    public static final String MOD_ID = "minebackup";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static SignalSubscriber knotLinkSubscriber;
    private static volatile MinecraftServer serverInstance;

    public static final String BROADCAST_APP_ID = "0x00000020";
    public static final String BROADCAST_SIGNAL_ID = "0x00000020";

    // 构造函数：在这里注册事件监听器
    public MineBackup() {
        // 将这个类的实例注册到 Forge 的主事件总线上
        MinecraftForge.EVENT_BUS.register(this);
        LOGGER.info("Initializing MineBackup for Forge...");
    }

    // 使用 @SubscribeEvent 注解来监听命令注册事件
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        Command.register(event.getDispatcher());
    }

    // 监听服务器启动事件
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("MineBackup Mod: Server is starting, initializing KnotLink Subscriber...");
        serverInstance = event.getServer();
        knotLinkSubscriber = new SignalSubscriber(BROADCAST_APP_ID, BROADCAST_SIGNAL_ID);
        knotLinkSubscriber.setSignalListener(this::handleBroadcastEvent);
        new Thread(knotLinkSubscriber::start).start();
    }

    // 监听服务器停止事件
    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        LOGGER.info("MineBackup Mod: Server is stopping, shutting down KnotLink Subscriber...");
        if (knotLinkSubscriber != null) {
            knotLinkSubscriber.stop();
        }
        serverInstance = null;
    }


    // --- 核心逻辑方法保持不变 ---

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
                serverInstance.getPlayerList().broadcastMessage(new TranslatableComponent("minebackup.message.remote_save.start"), ChatType.SYSTEM, Util.NIL_UUID);
                boolean allLevelsSaved = serverInstance.saveAllChunks(true, true, true);
                if (allLevelsSaved) {
                    serverInstance.getPlayerList().broadcastMessage(new TranslatableComponent("minebackup.message.remote_save.success"), ChatType.SYSTEM, Util.NIL_UUID);
                } else {
                    serverInstance.getPlayerList().broadcastMessage(new TranslatableComponent("minebackup.message.remote_save.fail"), ChatType.SYSTEM, Util.NIL_UUID);
                }
            });
            return;
        }

        LOGGER.info("Received MineBackup broadcast event: {}", payload);
        Map<String, String> eventData = parsePayload(payload);
        String eventType = eventData.get("event");
        if (eventType == null) return;

        Component message = null;
        switch (eventType) {
            case "backup_started":
                message = new TranslatableComponent("minebackup.broadcast.backup.started", eventData.getOrDefault("world", "Unknown World"));
                break;
            case "restore_started":
                message = new TranslatableComponent("minebackup.broadcast.restore.started", eventData.getOrDefault("world", "Unknown World"));
                break;
            case "backup_success":
                message = new TranslatableComponent("minebackup.broadcast.backup.success",
                        eventData.getOrDefault("world", "Unknown World"),
                        eventData.getOrDefault("file", "Unknown File"));
                break;
            case "backup_failed":
                message = new TranslatableComponent("minebackup.broadcast.backup.failed",
                        eventData.getOrDefault("world", "Unknown World"),
                        eventData.getOrDefault("error", "Unknown Error"));
                break;
            case "game_session_end":
                message = new TranslatableComponent("minebackup.broadcast.session.end", eventData.getOrDefault("world", "Unknown World"));
                break;
            case "auto_backup_started":
                message = new TranslatableComponent("minebackup.broadcast.auto_backup.started", eventData.getOrDefault("world", "Unknown World"));
                break;
        }

        if ("pre_hot_backup".equals(eventType)) {
            serverInstance.execute(() -> {
                LOGGER.info("Executing immediate save for pre_hot_backup event.");
                String worldName = eventData.getOrDefault("world", "Unknown World");
                serverInstance.getPlayerList().broadcastMessage(new TranslatableComponent("minebackup.broadcast.hot_backup.request", worldName), ChatType.SYSTEM, Util.NIL_UUID);
                boolean allSaved = serverInstance.saveAllChunks(true, true, true);
                if (!allSaved) {
                    LOGGER.warn("One or more levels failed to save during pre_hot_backup for world: {}", worldName);
                    serverInstance.getPlayerList().broadcastMessage(new TranslatableComponent("minebackup.broadcast.hot_backup.warn", worldName), ChatType.SYSTEM, Util.NIL_UUID);
                }
                LOGGER.info("World saved successfully for hot backup.");
                serverInstance.getPlayerList().broadcastMessage(new TranslatableComponent("minebackup.broadcast.hot_backup.complete"), ChatType.SYSTEM, Util.NIL_UUID);
            });
        } else if ("game_session_start".equals(eventType)) {
            LOGGER.info("MineBackup detected game session start for world: {}", eventData.getOrDefault("world", "Unknown World"));
        }

        if (message != null) {
            final Component finalMessage = message;
            serverInstance.execute(() -> serverInstance.getPlayerList().broadcastMessage(finalMessage, ChatType.SYSTEM, Util.NIL_UUID));
        }
    }
}