package com.leafuke.minebackup;

import com.leafuke.minebackup.knotlink.OpenSocketQuerier;
import com.leafuke.minebackup.knotlink.SignalSubscriber;
import com.leafuke.minebackup.knotlink.SignalSender;
import com.leafuke.minebackup.restore.HotRestoreState;
import com.leafuke.minebackup.compat.GcaCompat;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * MineBackup Mod 主入口类（Fabric 1.21.11+，使用 Mojang 官方映射）
 * 负责初始化 KnotLink 连接，注册命令和事件监听
 */
public class MineBackup implements ModInitializer {

    public static final String MOD_ID = "minebackup";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // KnotLink 订阅器实例
    private static SignalSubscriber knotLinkSubscriber = null;
    // 服务器实例（使用 volatile 保证线程安全）
    private static volatile MinecraftServer serverInstance;

    // KnotLink 通信 ID
    public static final String BROADCAST_APP_ID = "0x00000020";
    public static final String BROADCAST_SIGNAL_ID = "0x00000020";

    private static final String QUERIER_APP_ID = "0x00000020";
    private static final String QUERIER_SOCKET_ID = "0x00000010";

    @Override
    public void onInitialize() {
        LOGGER.info("[MineBackup] 正在初始化 Fabric 1.21.11+ 版本...");

        // 注册所有必要的事件回调
        registerServerLifecycleEvents();
        registerCommands();
    }

    /**
     * 注册命令
     */
    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            Command.register(dispatcher);
        });
    }

    /**
     * 注册服务器生命周期事件
     */
    private void registerServerLifecycleEvents() {
        // 服务器启动事件
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            // 如果已经有订阅器实例，不重复启动
            if (knotLinkSubscriber == null) {
                LOGGER.info("[MineBackup] 服务器正在启动，初始化 KnotLink 订阅器...");
                serverInstance = server;
                knotLinkSubscriber = new SignalSubscriber(BROADCAST_APP_ID, BROADCAST_SIGNAL_ID);
                knotLinkSubscriber.setSignalListener(this::handleBroadcastEvent);
                new Thread(knotLinkSubscriber::start).start();
            } else {
                LOGGER.info("[MineBackup] 服务器正在启动，KnotLink 订阅器已存在...");
                serverInstance = server;
                knotLinkSubscriber.setSignalListener(this::handleBroadcastEvent);
            }

            // 加载配置并启动自动备份（如果配置了的话）
            Config.load();
            if (Config.hasAutoBackup()) {
                String cmd = String.format("AUTO_BACKUP %d %d %d", Config.getConfigId(), Config.getWorldIndex(), Config.getInternalTime());
                OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, cmd);
                LOGGER.info("[MineBackup] 从配置发送自动备份请求: {}", cmd);
            }
        });

        // 服务器停止事件
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            // 仅在专用服务器上停止订阅器
            if (server.isDedicatedServer()) {
                if (knotLinkSubscriber != null) {
                    knotLinkSubscriber.stop();
                    knotLinkSubscriber = null;
                    LOGGER.info("[MineBackup] 服务器停止，已关闭 KnotLink 订阅器。");
                }
            }
        });
    }

    /**
     * 解析事件负载数据
     * @param payload 格式为 "key1=value1;key2=value2" 的字符串
     * @return 解析后的键值对映射
     */
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

    private Component getWorldDisplay(Map<String, String> eventData) {
        String world = eventData.get("world");
        if (world == null || world.isBlank()) {
            return Component.translatable("minebackup.message.unknown_world");
        }
        return Component.literal(world);
    }

    private Component getFileDisplay(Map<String, String> eventData) {
        String file = eventData.get("file");
        if (file == null || file.isBlank()) {
            return Component.translatable("minebackup.message.unknown_file");
        }
        return Component.literal(file);
    }

    private Component getErrorDisplay(Map<String, String> eventData) {
        String error = eventData.get("error");
        if (error == null || error.isBlank()) {
            return Component.translatable("minebackup.message.unknown_error");
        }
        return Component.literal(error);
    }

    /**
     * 尝试解析当前世界存档文件夹名
     */
    private String resolveLevelFolder(MinecraftServer server) {
        try {
            Path root = server.getWorldPath(LevelResource.ROOT);
            if (root != null && root.getFileName() != null) {
                return root.getFileName().toString();
            }
        } catch (Exception ignored) { }
        return server.getWorldData().getLevelName();
    }

    /**
     * 发送广播事件
     * @param event 事件内容
     */
    private static void BroadcastEvent(String event) {
        SignalSender sender = new SignalSender(BROADCAST_APP_ID, BROADCAST_SIGNAL_ID);
        sender.emitt(event);
    }

    /**
     * 处理从 MineBackup 主程序接收到的广播事件
     * @param payload 事件负载
     */
    private void handleBroadcastEvent(String payload) {
        if (serverInstance == null) return;

        // 处理远程保存命令
        if ("minebackup save".equals(payload)) {
            serverInstance.execute(() -> {
                LOGGER.info("[MineBackup] 收到远程保存命令，正在执行...");
                serverInstance.getPlayerList().broadcastSystemMessage(
                    Component.translatable("minebackup.message.remote_save.start"), false);
                boolean allLevelsSaved = serverInstance.saveAllChunks(true, true, true);
                if (allLevelsSaved) {
                    serverInstance.getPlayerList().broadcastSystemMessage(
                        Component.translatable("minebackup.message.remote_save.success"), false);
                } else {
                    serverInstance.getPlayerList().broadcastSystemMessage(
                        Component.translatable("minebackup.message.remote_save.fail"), false);
                }
            });
            return;
        }

        LOGGER.info("[MineBackup] 收到广播事件: {}", payload);
        Map<String, String> eventData = parsePayload(payload);
        String eventType = eventData.get("event");
        if (eventType == null) return;

        // 处理热还原前的准备事件
        if ("pre_hot_restore".equals(eventType)) {
            LOGGER.info("[MineBackup] 收到热还原准备请求");
            serverInstance.execute(() -> {
                serverInstance.getPlayerList().broadcastSystemMessage(
                    Component.translatable("minebackup.message.restore.preparing"), false);

                // 标记还原状态，避免重复触发
                HotRestoreState.isRestoring = true;
                HotRestoreState.waitingForServerStopAck = true;

                // 区分服务器类型
                if (serverInstance.isDedicatedServer()) {
                    // 专用服务器逻辑：踢出所有玩家并关闭服务器
                    LOGGER.info("[MineBackup] 检测到专用服务器，踢出所有玩家并停止服务器");
                    var playerList = serverInstance.getPlayerList().getPlayers();
                    Component kickMessage = Component.translatable("minebackup.message.restore.kick");

                    // 先保存世界数据，确保数据完整性
                    LOGGER.info("[MineBackup] 保存世界数据...");
                    boolean saveSuccess = serverInstance.saveAllChunks(true, true, true);
                    if (!saveSuccess) {
                        LOGGER.warn("[MineBackup] 世界保存可能不完整");
                    }

                    // 踢出所有玩家
                    for (var player : playerList.toArray(new ServerPlayer[0])) {
                        try {
                            player.connection.disconnect(kickMessage);
                        } catch (Exception e) {
                            LOGGER.warn("[MineBackup] 踢出玩家 {} 时出现异常: {}", player.getName().getString(), e.getMessage());
                        }
                    }

                    // 通知 MineBackup 主程序可以开始还原，稍微等待确保断开完成
                    new Thread(() -> {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                        OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, "SHUTDOWN_WORLD_SUCCESS");
                    }).start();

                    // 停止服务器
                    serverInstance.stopServer();
                } else {
                    // 单人游戏逻辑（参考 QuickBackupM-Reforged 实现）
                    LOGGER.info("[MineBackup] 检测到单人游戏，保存并断开连接");

                    // 1. 获取当前世界存档文件夹名称
                    String levelId = resolveLevelFolder(serverInstance);
                    MineBackupClient.worldToRejoin = levelId;
                    HotRestoreState.levelIdToRejoin = levelId;
                    LOGGER.info("[MineBackup] 保存世界ID用于自动重连: {}", levelId);

                    // 2. 保存游戏 - 使用同步保存确保数据完整
                    LOGGER.info("[MineBackup] 保存世界数据...");
                    boolean saveSuccess = serverInstance.saveAllChunks(true, true, true);
                    if (!saveSuccess) {
                        LOGGER.warn("[MineBackup] 世界保存可能不完整，但继续进行还原流程");
                    }

                    // 3. 踢出玩家（这将触发客户端断开连接，从而关闭集成服务器）
                    // 参考 QuickBackupM-Reforged: 使用 player.connection.disconnect()
                    var players = serverInstance.getPlayerList().getPlayers();
                    if (!players.isEmpty()) {
                        Component kickMessage = Component.translatable("minebackup.message.restore.kick");
                        for (var player : players.toArray(new ServerPlayer[0])) {
                            try {
                                LOGGER.info("[MineBackup] 断开玩家连接: {}", player.getName().getString());
                                player.connection.disconnect(kickMessage);
                            } catch (Exception e) {
                                LOGGER.warn("[MineBackup] 断开玩家 {} 时出现异常: {}", player.getName().getString(), e.getMessage());
                            }
                        }
                    }

                    // 4. 延迟通知 MineBackup 主程序，确保客户端已完全断开
                    // 使用单独线程避免阻塞服务器线程
                    new Thread(() -> {
                        try {
                            // 等待一小段时间确保断开连接完成
                            Thread.sleep(500);
                            OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, "SHUTDOWN_WORLD_SUCCESS");
                            LOGGER.info("[MineBackup] 已通知主程序可以开始还原");
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }).start();
                }
            });
            return;
        }

        // 收到还原完成信号
        if ("restore_finished".equals(eventType)) {
            // 主程序通知还原完成，触发自动重连
            MineBackupClient.readyToRejoin = true;
            if (HotRestoreState.levelIdToRejoin != null && MineBackupClient.worldToRejoin == null) {
                MineBackupClient.worldToRejoin = HotRestoreState.levelIdToRejoin;
            }
            HotRestoreState.waitingForServerStopAck = false;
            LOGGER.info("[MineBackup] 还原成功，准备自动重连");
            return;
        }

        // 构建要广播的消息
        final Component message = switch (eventType) {
            case "backup_started" -> Component.translatable("minebackup.broadcast.backup.started",
                getWorldDisplay(eventData));
            case "restore_started" -> Component.translatable("minebackup.broadcast.restore.started",
                getWorldDisplay(eventData));
            case "backup_success" -> Component.translatable("minebackup.broadcast.backup.success",
                getWorldDisplay(eventData), getFileDisplay(eventData));
            case "backup_failed" -> Component.translatable("minebackup.broadcast.backup.failed",
                getWorldDisplay(eventData), getErrorDisplay(eventData));
            case "game_session_end" -> Component.translatable("minebackup.broadcast.session.end",
                getWorldDisplay(eventData));
            case "auto_backup_started" -> Component.translatable("minebackup.broadcast.auto_backup.started",
                getWorldDisplay(eventData));
            case "we_snapshot_completed" -> Component.translatable("minebackup.broadcast.we_snapshot.completed",
                getWorldDisplay(eventData), getFileDisplay(eventData));
            default -> null;
        };

        // 处理热备份事件
        if ("pre_hot_backup".equals(eventType)) {
            serverInstance.execute(() -> {
                LOGGER.info("[MineBackup] 收到热备份请求，执行即时保存");
                // 在热备份前触发 GCA 假人保存（如果存在）
                GcaCompat.saveFakePlayersIfNeeded(serverInstance);
                String worldName = serverInstance.getWorldData().getLevelName();
                serverInstance.getPlayerList().broadcastSystemMessage(
                    Component.translatable("minebackup.broadcast.hot_backup.request", worldName), false);
                boolean allSaved = serverInstance.saveAllChunks(true, true, true);
                if (!allSaved) {
                    LOGGER.warn("[MineBackup] 部分数据保存失败，世界: {}", worldName);
                    serverInstance.getPlayerList().broadcastSystemMessage(
                        Component.translatable("minebackup.broadcast.hot_backup.warn", worldName), false);
                }
                LOGGER.info("[MineBackup] 世界数据保存完成");
                serverInstance.getPlayerList().broadcastSystemMessage(
                    Component.translatable("minebackup.broadcast.hot_backup.complete"), false);
            });
        } else if ("game_session_start".equals(eventType)) {
            LOGGER.info("[MineBackup] 检测到游戏会话开始，世界: {}", getWorldDisplay(eventData).getString());
        }

        // 广播消息给所有玩家
        if (message != null) {
            serverInstance.execute(() -> serverInstance.getPlayerList().broadcastSystemMessage(message, false));
        }
    }
}
