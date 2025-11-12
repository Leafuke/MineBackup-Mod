package com.leafuke.minebackup;

import com.leafuke.minebackup.knotlink.OpenSocketQuerier;
import com.leafuke.minebackup.knotlink.SignalSubscriber;
import com.leafuke.minebackup.knotlink.SignalSender;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.text.Text;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

public class MineBackup implements ModInitializer {

    public static final String MOD_ID = "minebackup";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // --- From your existing code ---
    private static SignalSubscriber knotLinkSubscriber = null;
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
            // 如果已经有实例在运行，就不重复启动
            if (knotLinkSubscriber == null) {
                LOGGER.info("MineBackup Mod: Server is starting, initializing KnotLink Subscriber...");
                serverInstance = server;
                knotLinkSubscriber = new SignalSubscriber(BROADCAST_APP_ID, BROADCAST_SIGNAL_ID);
                knotLinkSubscriber.setSignalListener(this::handleBroadcastEvent);
                new Thread(knotLinkSubscriber::start).start();
            }
            else
            {
                LOGGER.info("MineBackup Mod: Server is starting, KnotLink Subscriber has been here...");
                serverInstance = server;
                knotLinkSubscriber.setSignalListener(this::handleBroadcastEvent);
            }
        });

//         Fabric's equivalent of ServerStoppingEvent
        // 为了接收还原成功信号，服务器停止时不关闭订阅。
//        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
//            LOGGER.info("MineBackup Mod: Server is stopping, shutting down KnotLink Subscriber...");
//            if (knotLinkSubscriber != null) {
//                knotLinkSubscriber.stop();
//            }
//            serverInstance = null;
//        });
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
                serverInstance.getPlayerManager().broadcast(Text.translatable("minebackup.message.remote_save.start"), false);
                boolean allLevelsSaved = serverInstance.save(true, true, true);
                if (allLevelsSaved) {
                    serverInstance.getPlayerManager().broadcast(Text.translatable("minebackup.message.remote_save.success"), false);
                } else {
                    serverInstance.getPlayerManager().broadcast(Text.translatable("minebackup.message.remote_save.fail"), false);
                }
            });
            return;
        }

        LOGGER.info("Received MineBackup broadcast event: {}", payload);
        Map<String, String> eventData = parsePayload(payload);
        String eventType = eventData.get("event");
        if (eventType == null) return;

        if ("pre_hot_restore".equals(eventType)) {
            LOGGER.info("Received 'pre_hot_restore'. Preparing for hot restore.");
            serverInstance.execute(() -> {
                serverInstance.getPlayerManager().broadcast(Text.translatable("minebackup.message.restore.preparing"), false);

                // 区分服务器类型
                if (serverInstance.isDedicated()) {
                    // 专用服务器逻辑：踢出所有玩家并关闭服务器
                    LOGGER.info("Dedicated server detected. Kicking all players and stopping.");
                    var playerList = serverInstance.getPlayerManager().getPlayerList();
                    Text kickMessage = Text.translatable("minebackup.message.restore.kick");
                    for (ServerPlayerEntity player : playerList) {
                        player.networkHandler.disconnect(kickMessage);
                    }
                    // 通知MineBackup可以开始了
                    OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, "SHUTDOWN_WORLD_SUCCESS");
                    serverInstance.stop(false); // 关闭服务器
                } else {
                    // 单人游戏逻辑：保存、踢出玩家返回标题界面
                    LOGGER.info("Single-player instance detected. Saving and disconnecting player.");

                    // 1. 保存当前世界ID到客户端暂存区
                    String levelId = serverInstance.getSaveProperties().getLevelName();
                    MineBackupClient.worldToRejoin = levelId;

                    // 2. 保存游戏
                    serverInstance.save(true, false, true);

                    // 3. 踢出玩家 (这将自动关闭集成服务器)
                    ServerPlayerEntity singlePlayer = serverInstance.getPlayerManager().getPlayerList().get(0);
                    Text kickMessage = Text.translatable("minebackup.message.restore.kick");
                    singlePlayer.networkHandler.disconnect(kickMessage);

                    // 4. 通知MineBackup可以开始了
                    OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, "SHUTDOWN_WORLD_SUCCESS");
                }
            });
            return;
        }

        // 收到还原完成信号
        if ("restore_finished".equals(eventType)) {
            MineBackupClient.readyToRejoin = true;
            LOGGER.info("Restore successful. Flagging client to rejoin world.");
            return;
        }

        final Text message = switch (eventType) {
            case "backup_started" -> Text.translatable("minebackup.broadcast.backup.started", eventData.getOrDefault("world", "Unknown World"));
            case "restore_started" -> Text.translatable("minebackup.broadcast.restore.started", eventData.getOrDefault("world", "Unknown World"));
            case "backup_success" -> Text.translatable("minebackup.broadcast.backup.success", eventData.getOrDefault("world", "Unknown World"), eventData.getOrDefault("file", "未知文件"));
            case "backup_failed" -> Text.translatable("minebackup.broadcast.backup.failed", eventData.getOrDefault("world", "Unknown World"), eventData.getOrDefault("error", "未知错误"));
            case "game_session_end" -> Text.translatable("minebackup.broadcast.session.end", eventData.getOrDefault("world", "Unknown World"));
            case "auto_backup_started" -> Text.translatable("minebackup.broadcast.auto_backup.started", eventData.getOrDefault("world", "Unknown World"));
            case "we_snapshot_completed" -> Text.translatable("minebackup.broadcast.we_snapshot.completed", eventData.getOrDefault("world", "Unknown World"), eventData.getOrDefault("file", "未知文件"));
            default -> null;
        };


        if ("pre_hot_backup".equals(eventType)) {
            serverInstance.execute(() -> {
                LOGGER.info("Executing immediate save for pre_hot_backup event.");
                String worldName = serverInstance.getSaveProperties().getLevelName();
                serverInstance.getPlayerManager().broadcast(Text.translatable("minebackup.broadcast.hot_backup.request", worldName), false);
                boolean allSaved = serverInstance.save(true, true, true);
                if (!allSaved) {
                    LOGGER.warn("One or more levels failed to save during pre_hot_backup for world: {}", worldName);
                    serverInstance.getPlayerManager().broadcast(Text.translatable("minebackup.broadcast.hot_backup.warn", worldName), false);
                }
                LOGGER.info("World saved successfully for hot backup.");
                serverInstance.getPlayerManager().broadcast(Text.translatable("minebackup.broadcast.hot_backup.complete"), false);
            });
        } else if ("game_session_start".equals(eventType)) {
            LOGGER.info("MineBackup detected game session start for world: {}", eventData.getOrDefault("world", "Unknown World"));
        }

        if (message != null) {
            serverInstance.execute(() -> serverInstance.getPlayerManager().broadcast(message, false));
        }
    }

}