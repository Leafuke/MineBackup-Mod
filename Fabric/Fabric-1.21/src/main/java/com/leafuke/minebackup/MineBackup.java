package com.leafuke.minebackup;

import com.leafuke.minebackup.knotlink.OpenSocketQuerier;
import com.leafuke.minebackup.knotlink.SignalSubscriber;
import com.leafuke.minebackup.knotlink.SignalSender;
import com.leafuke.minebackup.restore.HotRestoreState;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
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

            Config.load();
            if (Config.hasAutoBackup()) {
                String cmd = String.format("AUTO_BACKUP %d %d %d", Config.getConfigId(), Config.getWorldIndex(), Config.getInternalTime());
                OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, cmd);
                LOGGER.info("Sent auto backup request from config: {}", cmd);
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (server.isDedicated()) {
                if (knotLinkSubscriber != null) {
                    knotLinkSubscriber.stop();
                    knotLinkSubscriber = null;
                    LOGGER.info("MineBackup Mod: Server stopping, stopped KnotLink Subscriber.");
                }
            }
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

    private Text getWorldDisplay(Map<String, String> eventData) {
        String world = eventData.get("world");
        if (world == null || world.isBlank()) {
            return Text.translatable("minebackup.message.unknown_world");
        }
        return Text.literal(world);
    }

    private Text getFileDisplay(Map<String, String> eventData) {
        String file = eventData.get("file");
        if (file == null || file.isBlank()) {
            return Text.translatable("minebackup.message.unknown_file");
        }
        return Text.literal(file);
    }

    private Text getErrorDisplay(Map<String, String> eventData) {
        String error = eventData.get("error");
        if (error == null || error.isBlank()) {
            return Text.translatable("minebackup.message.unknown_error");
        }
        return Text.literal(error);
    }

    /**
     * 尝试解析当前世界存档文件夹名，优先使用根路径文件夹名称。
     */
    private String resolveLevelFolder(MinecraftServer server) {
        try {
            Path root = server.getSavePath(WorldSavePath.ROOT);
            if (root != null && root.getFileName() != null) {
                return root.getFileName().toString();
            }
        } catch (Exception ignored) { }
        return server.getSaveProperties().getLevelName();
    }

    private static void BroadcastEvent(String event) {
        SignalSender sender = new SignalSender(BROADCAST_APP_ID, BROADCAST_SIGNAL_ID);
        sender.emitt(event);
    }

    // Reflection-based invocation to avoid compile-time dependency on GcaCompat in environments where analysis fails
    private static void trySaveGcaFakePlayers(MinecraftServer server) {
        if (server == null) return;
        try {
            Class<?> clazz = Class.forName("com.leafuke.minebackup.compat.GcaCompat");
            java.lang.reflect.Method m = clazz.getMethod("saveFakePlayersIfNeeded", MinecraftServer.class);
            m.invoke(null, server);
        } catch (ClassNotFoundException ignored) {
            // GcaCompat not present on classpath - nothing to do
        } catch (Throwable t) {
            LOGGER.warn("Failed to invoke GcaCompat.saveFakePlayersIfNeeded: {}", t.getMessage());
        }
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

                // 标记还原状态，避免重复触发
                HotRestoreState.isRestoring = true;
                HotRestoreState.waitingForServerStopAck = true;

                if (serverInstance.isDedicated()) {
                    // 专用服务器逻辑：保存、踢出玩家并停止服务器
                    LOGGER.info("Dedicated server detected. Saving, kicking players, then stopping.");

                    boolean saveSuccess = serverInstance.save(true, true, true);
                    if (!saveSuccess) {
                        LOGGER.warn("World save may be incomplete before hot restore on dedicated server.");
                    }

                    var playerList = serverInstance.getPlayerManager().getPlayerList();
                    Text kickMessage = Text.translatable("minebackup.message.restore.kick");
                    for (ServerPlayerEntity player : playerList.toArray(new ServerPlayerEntity[0])) {
                        try {
                            player.networkHandler.disconnect(kickMessage);
                        } catch (Exception e) {
                            LOGGER.warn("Failed to disconnect player {}", e.getMessage());
                        }
                    }

                    // 通知 MineBackup 可以开始还原，稍微等待确保断开完成
                    new Thread(() -> {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                        OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, "SHUTDOWN_WORLD_SUCCESS");
                    }).start();

                    serverInstance.stop(false);
                } else {
                    // 单人游戏逻辑：保存、踢出玩家返回标题界面
                    LOGGER.info("Single-player instance detected. Saving and disconnecting player.");

                    String levelId = resolveLevelFolder(serverInstance);
                    MineBackupClient.worldToRejoin = levelId;
                    HotRestoreState.levelIdToRejoin = levelId;
                    LOGGER.info("Captured level folder for auto rejoin: {}", levelId);

                    boolean saveSuccess = serverInstance.save(true, true, true);
                    if (!saveSuccess) {
                        LOGGER.warn("World save may be incomplete before hot restore on singleplayer.");
                    }

                    var players = serverInstance.getPlayerManager().getPlayerList();
                    Text kickMessage = Text.translatable("minebackup.message.restore.kick");
                    for (ServerPlayerEntity player : players.toArray(new ServerPlayerEntity[0])) {
                        try {
                            player.networkHandler.disconnect(kickMessage);
                        } catch (Exception e) {
                            LOGGER.warn("Failed to disconnect player {}",  e.getMessage());
                        }
                    }

                    // 通过短暂延迟确保客户端完全退出世界后再通知主程序
                    new Thread(() -> {
                        try {
                            Thread.sleep(400);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                        OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, "SHUTDOWN_WORLD_SUCCESS");
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
            LOGGER.info("Restore successful. Flagging client to rejoin world.");
            return;
        }

        final Text message = switch (eventType) {
            case "backup_started" -> Text.translatable("minebackup.broadcast.backup.started", getWorldDisplay(eventData));
            case "restore_started" -> Text.translatable("minebackup.broadcast.restore.started", getWorldDisplay(eventData));
            case "backup_success" -> Text.translatable("minebackup.broadcast.backup.success", getWorldDisplay(eventData), getFileDisplay(eventData));
            case "backup_failed" -> Text.translatable("minebackup.broadcast.backup.failed", getWorldDisplay(eventData), getErrorDisplay(eventData));
            case "game_session_end" -> Text.translatable("minebackup.broadcast.session.end", getWorldDisplay(eventData));
            case "auto_backup_started" -> Text.translatable("minebackup.broadcast.auto_backup.started", getWorldDisplay(eventData));
            case "we_snapshot_completed" -> Text.translatable("minebackup.broadcast.we_snapshot.completed", getWorldDisplay(eventData), getFileDisplay(eventData));
            default -> null;
        };


        if ("pre_hot_backup".equals(eventType)) {
            serverInstance.execute(() -> {
                LOGGER.info("Executing immediate save for pre_hot_backup event.");
                // 在热备份前触发 GCA 假人保存（如果存在）
                trySaveGcaFakePlayers(serverInstance);
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