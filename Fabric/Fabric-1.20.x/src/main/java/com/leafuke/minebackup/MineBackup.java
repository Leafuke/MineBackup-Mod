package com.leafuke.minebackup;

import com.leafuke.minebackup.knotlink.OpenSocketQuerier;
import com.leafuke.minebackup.knotlink.SignalSubscriber;
import com.leafuke.minebackup.knotlink.SignalSender;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

public class MineBackup implements ModInitializer {

    public static final String MOD_ID = "minebackup";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // --- From your existing code ---
    private static SignalSubscriber knotLinkSubscriber;
    private static volatile MinecraftServer serverInstance; // Use volatile for thread safety

    // KnotLink Communication IDs
    public static final String BROADCAST_APP_ID = "0x00000020";
    public static final String BROADCAST_SIGNAL_ID = "0x00000020";

    private static final String QUERIER_APP_ID = "0x00000020";
    private static final String QUERIER_SOCKET_ID = "0x00000010";

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing MineBackup for Fabric...");

        // Register all necessary event callbacks
        registerServerLifecycleEvents();
        registerCommands();
    }

    private void registerCommands() {
        // Fabric's way to register commands. This is called when the server is ready for commands.
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            Command.register(dispatcher); // Your command logic is unchanged!
        });
    }

    private void registerServerLifecycleEvents() {
        // Fabric's equivalent of ServerStartingEvent
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            LOGGER.info("MineBackup Mod: Server is starting, initializing KnotLink Subscriber...");
            serverInstance = server;
            knotLinkSubscriber = new SignalSubscriber(BROADCAST_APP_ID, BROADCAST_SIGNAL_ID);
            knotLinkSubscriber.setSignalListener(this::handleBroadcastEvent);
            new Thread(knotLinkSubscriber::start).start();
        });

        // Fabric's equivalent of ServerStoppingEvent
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            LOGGER.info("MineBackup Mod: Server is stopping, shutting down KnotLink Subscriber...");
            if (knotLinkSubscriber != null) {
                knotLinkSubscriber.stop();
            }
            serverInstance = null;
        });
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

    private static void BroadcastEvent(String event) {
        SignalSender sender = new SignalSender(BROADCAST_APP_ID, BROADCAST_SIGNAL_ID);
        sender.emitt(event);
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
            case "backup_started" -> Component.translatable("minebackup.broadcast.backup.started", eventData.getOrDefault("world", "Unknown World"));
            case "restore_started" -> Component.translatable("minebackup.broadcast.restore.started", eventData.getOrDefault("world", "Unknown World"));
            case "backup_success" -> Component.translatable("minebackup.broadcast.backup.success", eventData.getOrDefault("world", "Unknown World"), eventData.getOrDefault("file", "未知文件"));
            case "backup_failed" -> Component.translatable("minebackup.broadcast.backup.failed", eventData.getOrDefault("world", "Unknown World"), eventData.getOrDefault("error", "未知错误"));
            case "game_session_end" -> Component.translatable("minebackup.broadcast.session.end", eventData.getOrDefault("world", "Unknown World"));
            case "auto_backup_started" -> Component.translatable("minebackup.broadcast.auto_backup.started", eventData.getOrDefault("world", "Unknown World"));
            case "pre_hot_restore" -> {
                LOGGER.info("Received 'RESTORE_CURRENT' signal. Initiating server shutdown for restoration.");
                serverInstance.execute(() -> {
                    // 1. 获取所有玩家
                    var playerList = serverInstance.getPlayerList().getPlayers();
                    // 2. 创建踢出消息
                    Component kickMessage = Component.translatable("minebackup.message.restore.kick");
                    // 3. 踢出所有玩家
                    for (var player : playerList) {
                        player.connection.disconnect(kickMessage);
                    }
                    // 4. 安全地关闭服务器
                    serverInstance.stopServer();
                });
                // 用broadcast向MineBackup发送广播BACKUP_CURRENT
                BroadcastEvent("RESTORE_CURRENT");
                OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, "SHUTDOWN_WORLD_SUCCESS");
                yield null;
            }
            default -> null;
        };

        if ("pre_hot_backup".equals(eventType)) {
            serverInstance.execute(() -> {
                LOGGER.info("Executing immediate save for pre_hot_backup event.");
                String worldName = eventData.getOrDefault("world", "Unknown World");
                serverInstance.getPlayerList().broadcastSystemMessage(Component.translatable("minebackup.broadcast.hot_backup.request", worldName), false);
                boolean allSaved = serverInstance.saveAllChunks(true, true, true);
                if (!allSaved) {
                    LOGGER.warn("One or more levels failed to save during pre_hot_backup for world: {}", worldName);
                    serverInstance.getPlayerList().broadcastSystemMessage(Component.translatable("minebackup.broadcast.hot_backup.warn", worldName), false);
                }
                LOGGER.info("World saved successfully for hot backup.");
                serverInstance.getPlayerList().broadcastSystemMessage(Component.translatable("minebackup.broadcast.hot_backup.complete"), false);
            });
        } else if ("game_session_start".equals(eventType)) {
            LOGGER.info("MineBackup detected game session start for world: {}", eventData.getOrDefault("world", "Unknown World"));
        }

        if (message != null) {
            serverInstance.execute(() -> serverInstance.getPlayerList().broadcastSystemMessage(message, false));
        }
    }

}