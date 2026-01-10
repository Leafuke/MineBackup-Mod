package com.leafuke.minebackup;

import com.leafuke.minebackup.knotlink.OpenSocketQuerier;
import com.leafuke.minebackup.knotlink.SignalSubscriber;
import com.leafuke.minebackup.knotlink.SignalSender;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

// 使用 @Mod 注解，这是 NeoForge 的入口点
@Mod(MineBackup.MOD_ID)
public class MineBackup {

    public static final String MOD_ID = "minebackup";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static SignalSubscriber knotLinkSubscriber = null;
    private static volatile MinecraftServer serverInstance;

    public static final String BROADCAST_APP_ID = "0x00000020";
    public static final String BROADCAST_SIGNAL_ID = "0x00000020";

    private static final String QUERIER_APP_ID = "0x00000020";
    private static final String QUERIER_SOCKET_ID = "0x00000010";

    // 模组构造函数
    public MineBackup(IEventBus modEventBus, Dist dist) {
        LOGGER.info("Initializing MineBackup for NeoForge...");

        // 注册服务器生命周期事件和命令事件
        // 这些事件在通用事件总线上
        NeoForge.EVENT_BUS.register(this);

        // 如果在客户端环境中，则初始化客户端逻辑
        if (dist.isClient()) {
            MineBackupClient.initialize();
        }
    }

    // 监听命令注册事件，替代 Fabric 的 CommandRegistrationCallback
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        Command.register(event.getDispatcher(), event.getBuildContext(), event.getCommandSelection());
    }

    // 监听服务器启动事件，替代 Fabric 的 ServerLifecycleEvents.SERVER_STARTING
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        if (knotLinkSubscriber == null) {
            LOGGER.info("MineBackup Mod: Server is starting, initializing KnotLink Subscriber...");
            serverInstance = event.getServer();
            knotLinkSubscriber = new SignalSubscriber(BROADCAST_APP_ID, BROADCAST_SIGNAL_ID);
            knotLinkSubscriber.setSignalListener(this::handleBroadcastEvent);
            new Thread(knotLinkSubscriber::start).start();
        }

        Config.load();
        if (Config.hasAutoBackup()) {
            String cmd = String.format("AUTO_BACKUP %d %d %d", Config.getConfigId(), Config.getWorldIndex(), Config.getInternalTime());
            OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, cmd);
            LOGGER.info("Sent auto backup request from config: {}", cmd);
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (event.getServer().isDedicatedServer()) {
            if (knotLinkSubscriber != null) {
                knotLinkSubscriber.stop();
                knotLinkSubscriber = null;
                LOGGER.info("MineBackup Mod: Server stopping, stopped KnotLink Subscriber.");
            }
        }
    }

    // ----- 其余逻辑基本保持不变 -----

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

        if ("pre_hot_restore".equals(eventType)) {
            LOGGER.info("Received 'pre_hot_restore'. Preparing for hot restore.");
            serverInstance.execute(() -> {
                serverInstance.getPlayerList().broadcastSystemMessage(Component.translatable("minebackup.message.restore.preparing"), false);

                if (serverInstance.isDedicatedServer()) {
                    LOGGER.info("Dedicated server detected. Kicking all players and stopping.");
                    var playerList = serverInstance.getPlayerList().getPlayers();
                    Component kickMessage = Component.translatable("minebackup.message.restore.kick");
                    for (var player : playerList) {
                        player.connection.disconnect(kickMessage);
                    }
                    OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, "SHUTDOWN_WORLD_SUCCESS");
                    serverInstance.stopServer();
                } else {
                    LOGGER.info("Single-player instance detected. Saving and disconnecting player.");
                    String levelId = serverInstance.getWorldPath(LevelResource.ROOT).getFileName().toString();
                    MineBackupClient.worldToRejoin = levelId;
                    serverInstance.saveAllChunks(true, false, true);
                    ServerPlayer singlePlayer = serverInstance.getPlayerList().getPlayers().get(0);
                    Component kickMessage = Component.translatable("minebackup.message.restore.kick");
                    singlePlayer.connection.disconnect(kickMessage);
                    OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, "SHUTDOWN_WORLD_SUCCESS");
                }
            });
            return;
        }

        if ("restore_finished".equals(eventType)) {
            MineBackupClient.readyToRejoin = true;
            LOGGER.info("Restore successful. Flagging client to rejoin world.");
            return;
        }

        String levelId = serverInstance.getWorldData().getLevelName();
        final Component message = switch (eventType) {
            case "backup_started" -> Component.translatable("minebackup.broadcast.backup.started", eventData.getOrDefault("world", "Unknown World"));
            case "restore_started" -> Component.translatable("minebackup.broadcast.restore.started", eventData.getOrDefault("world", "Unknown World"));
            case "backup_success" -> Component.translatable("minebackup.broadcast.backup.success", eventData.getOrDefault("world", "Unknown World"), eventData.getOrDefault("file", "未知文件"));
            case "backup_failed" -> Component.translatable("minebackup.broadcast.backup.failed", eventData.getOrDefault("world", "Unknown World"), eventData.getOrDefault("error", "未知错误"));
            case "game_session_end" -> Component.translatable("minebackup.broadcast.session.end", eventData.getOrDefault("world", "Unknown World"));
            case "auto_backup_started" -> Component.translatable("minebackup.broadcast.auto_backup.started", eventData.getOrDefault("world", "Unknown World"));
            case "we_snapshot_completed" -> Component.translatable("minebackup.broadcast.we_snapshot.completed", eventData.getOrDefault("world", "Unknown World"), eventData.getOrDefault("file", "未知文件"));
            default -> null;
        };

        if ("pre_hot_backup".equals(eventType)) {
            serverInstance.execute(() -> {
                LOGGER.info("Executing immediate save for pre_hot_backup event.");
                String worldName = levelId;
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
            LOGGER.info("MineBackup detected game session start for world: {}", levelId);
        }

        if (message != null) {
            serverInstance.execute(() -> serverInstance.getPlayerList().broadcastSystemMessage(message, false));
        }
    }
}

