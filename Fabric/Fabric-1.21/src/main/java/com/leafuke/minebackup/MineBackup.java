package com.leafuke.minebackup;

import com.leafuke.minebackup.knotlink.OpenSocketQuerier;
import com.leafuke.minebackup.knotlink.SignalSubscriber;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class MineBackup implements ModInitializer {

    public static final String MOD_ID = "minebackup";
    // KnotLink 协议版本号，用于与主程序握手时进行版本兼容性检查
    public static final String MOD_VERSION = "1.0.0";
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
     * 尝试解析当前世界存档文件夹名。
     *
     * 说明：
     * 1) 自动重进必须使用“存档文件夹名”，不能使用世界显示名。
     * 2) 部分环境下 ROOT 路径可能不是最终世界根目录，因此需要向上回溯 level.dat。
     * 3) 目录名可能包含中文或英文，直接保留 Java 字符串，不做编码转换。
     */
    private String resolveLevelFolder(MinecraftServer server) {
        String levelIdFromPath = null;
        try {
            Path root = server.getSavePath(WorldSavePath.ROOT);
            levelIdFromPath = resolveLevelIdFromPath(root);
        } catch (Exception ignored) { }

        if (isValidLevelId(levelIdFromPath)) {
            return levelIdFromPath;
        }

        // 回退：仅在无法解析路径时才使用显示名，避免返回 null 导致后续重进流程中断
        String levelName = server.getSaveProperties().getLevelName();
        if (isValidLevelId(levelName)) {
            return levelName;
        }
        return "world";
    }

    /**
     * 从给定路径中解析真正的世界目录名。
     * 如果当前路径不是世界根目录，会尝试向上回溯查找包含 level.dat 的目录。
     */
    private String resolveLevelIdFromPath(Path path) {
        if (path == null) {
            return null;
        }

        Path cursor = path;
        for (int i = 0; i < 6 && cursor != null; i++) {
            if (Files.exists(cursor.resolve("level.dat"))) {
                Path fileName = cursor.getFileName();
                if (fileName != null) {
                    String levelId = fileName.toString();
                    if (isValidLevelId(levelId)) {
                        return levelId;
                    }
                }
            }
            cursor = cursor.getParent();
        }

        Path fileName = path.getFileName();
        if (fileName == null) {
            return null;
        }
        String levelId = fileName.toString();
        return isValidLevelId(levelId) ? levelId : null;
    }

    /**
     * 校验世界目录名是否可用于自动重进。
     * 禁止 "." / ".." 以及带路径分隔符的值，避免被解释为当前目录或非法路径。
     */
    private boolean isValidLevelId(String levelId) {
        if (levelId == null) {
            return false;
        }
        String normalized = levelId.trim();
        if (normalized.isBlank()) {
            return false;
        }
        if (".".equals(normalized) || "..".equals(normalized)) {
            return false;
        }
        return !normalized.contains("/") && !normalized.contains("\\");
    }

    /**
     * 选择最终用于重进的 levelId：优先服务端路径解析，失败时回退到事件 world 字段。
     */
    private String resolveRejoinLevelId(MinecraftServer server, String eventWorld) {
        String resolved = resolveLevelFolder(server);
        if (isValidLevelId(resolved)) {
            return resolved;
        }
        if (isValidLevelId(eventWorld)) {
            return eventWorld.trim();
        }
        return "world";
    }

    /**
     * 热备份前执行“完整保存”。
     *
     * 说明：不同映射/版本下方法名可能不同，这里按优先级尝试：
     * 1) saveEverything（优先，通常包含 level.dat 等全局元数据）
     * 2) save
     * 3) saveAllChunks（最后回退）
     */
    private boolean saveAllDataForHotBackup(MinecraftServer server) {
        if (server == null) {
            return false;
        }
        Boolean byEverything = invokeServerSaveMethod(server, "saveEverything");
        if (byEverything != null) {
            return byEverything;
        }
        Boolean bySave = invokeServerSaveMethod(server, "save");
        if (bySave != null) {
            return bySave;
        }
        Boolean byChunks = invokeServerSaveMethod(server, "saveAllChunks");
        return byChunks != null && byChunks;
    }

    /**
     * 反射调用服务器保存方法，返回：
     * - true/false：方法调用成功并有布尔结果
     * - null：方法不存在或调用失败（交给上层继续回退）
     */
    private Boolean invokeServerSaveMethod(MinecraftServer server, String methodName) {
        try {
            java.lang.reflect.Method method = server.getClass().getMethod(
                    methodName, boolean.class, boolean.class, boolean.class);
            Object result = method.invoke(server, true, true, true);
            if (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class) {
                return Boolean.TRUE.equals(result);
            }
            // 某些版本可能返回 void，调用成功即视为成功
            return true;
        } catch (NoSuchMethodException ignored) {
            return null;
        } catch (Throwable t) {
            LOGGER.warn("调用 {} 进行完整保存时失败: {}", methodName, t.getMessage());
            return null;
        }
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

        // ========== KnotLink 新协议：处理主程序发来的握手请求 ==========
        if ("handshake".equals(eventType)) {
            String mainVersion = eventData.get("version");
            String action = eventData.get("action");
            String world = eventData.get("world");
            String minModVersion = eventData.get("min_mod_version");

            LOGGER.info("Received handshake from MineBackup v{}, action={}, world={}, min_mod_version={}",
                    mainVersion, action, world, minModVersion);

            // 存储握手信息
            HotRestoreState.mainProgramVersion = mainVersion;
            HotRestoreState.handshakeCompleted = true;
            HotRestoreState.requiredMinModVersion = minModVersion;

            // 检查版本兼容性：模组版本是否满足主程序的最低要求；主程序是否满足模组的最低要求
            boolean compatible = isVersionCompatible(MOD_VERSION, minModVersion);
            HotRestoreState.versionCompatible = compatible;


            // 回复握手响应，携带模组版本号
            OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, "HANDSHAKE_RESPONSE " + MOD_VERSION);
            LOGGER.info("Sent HANDSHAKE_RESPONSE with mod version {}", MOD_VERSION);

            if (!isVersionCompatible(mainVersion, "1.13.0")) {
                serverInstance.execute(() -> {
                    serverInstance.getPlayerManager().broadcast(
                            Text.translatable("minebackup.message.handshake.main_version_incompatible",
                                    mainVersion, "1.13.0"), false);
                });
                return;
            }

            // 版本不兼容时，向游戏内玩家发出警告
            if (!compatible) {
                try {
                    serverInstance.execute(() -> {
                        serverInstance.getPlayerManager().broadcast(
                                Text.translatable("minebackup.message.handshake.version_incompatible",
                                        MOD_VERSION, minModVersion != null ? minModVersion : "?"), false);
                    });
                } catch (Exception ignored) { }
                LOGGER.warn("Mod version {} does not meet minimum requirement {}", MOD_VERSION, minModVersion);
            } else {
                // 版本兼容，显示连接成功提示
                try {
                    serverInstance.execute(() -> {
                        serverInstance.getPlayerManager().broadcast(
                                Text.translatable("minebackup.message.handshake.success",
                                        mainVersion != null ? mainVersion : "?"), false);
                    });
                } catch (Exception ignored) { }
            }
            return;
        }

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
                        // KnotLink 新协议：发送 WORLD_SAVE_AND_EXIT_COMPLETE（兼容旧版 SHUTDOWN_WORLD_SUCCESS）
                        OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, "WORLD_SAVE_AND_EXIT_COMPLETE");
//                        OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, "SHUTDOWN_WORLD_SUCCESS"); // 旧的版本兼容信号，不应该同时发送，会导致信息重合
                        LOGGER.info("Sent WORLD_SAVE_AND_EXIT_COMPLETE to MineBackup main program (dedicated).");
                    }).start();

                    serverInstance.stop(false);
                } else {
                    // 单人游戏逻辑：保存、踢出玩家返回标题界面
                    LOGGER.info("Single-player instance detected. Saving and disconnecting player.");

                    String levelId = resolveRejoinLevelId(serverInstance, eventData.get("world"));
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
                        // KnotLink 新协议：发送 WORLD_SAVE_AND_EXIT_COMPLETE
                        OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, "WORLD_SAVE_AND_EXIT_COMPLETE");
//                        OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, "SHUTDOWN_WORLD_SUCCESS");
                        LOGGER.info("Sent WORLD_SAVE_AND_EXIT_COMPLETE to MineBackup main program (singleplayer).");
                    }).start();
                }
            });
            return;
        }

        // ========== 收到还原完成信号 ==========
        if ("restore_finished".equals(eventType)) {
            String status = eventData.getOrDefault("status", "success");
            if ("success".equals(status)) {
                String worldFromEvent = eventData.get("world");
                if (isValidLevelId(worldFromEvent)) {
                    String fallbackLevelId = worldFromEvent.trim();
                    if (!isValidLevelId(HotRestoreState.levelIdToRejoin)) {
                        HotRestoreState.levelIdToRejoin = fallbackLevelId;
                    }
                    if (!isValidLevelId(MineBackupClient.worldToRejoin)) {
                        MineBackupClient.worldToRejoin = fallbackLevelId;
                    }
                }
                // 主程序通知还原成功，准备自动重连
                MineBackupClient.readyToRejoin = true;
                if (HotRestoreState.levelIdToRejoin != null && MineBackupClient.worldToRejoin == null) {
                    MineBackupClient.worldToRejoin = HotRestoreState.levelIdToRejoin;
                }
                HotRestoreState.waitingForServerStopAck = false;
                LOGGER.info("还原成功，已标记客户端准备重新加入世界");
            } else {
                // 还原失败
                LOGGER.warn("主程序报告还原失败，status={}", status);
                MineBackupClient.readyToRejoin = false;
                HotRestoreState.reset();
            }
            return;
        }

        // ========== KnotLink 新协议：处理主程序请求重新加入世界 ==========
        if ("rejoin_world".equals(eventType)) {
            LOGGER.info("收到 rejoin_world 事件，触发客户端重连");
            String worldFromEvent = eventData.get("world");
            if (isValidLevelId(worldFromEvent)) {
                String fallbackLevelId = worldFromEvent.trim();
                if (!isValidLevelId(HotRestoreState.levelIdToRejoin)) {
                    HotRestoreState.levelIdToRejoin = fallbackLevelId;
                }
                if (!isValidLevelId(MineBackupClient.worldToRejoin)) {
                    MineBackupClient.worldToRejoin = fallbackLevelId;
                }
            }
            // 使用之前保存的 levelId 重新加入世界
            if (HotRestoreState.levelIdToRejoin != null && MineBackupClient.worldToRejoin == null) {
                MineBackupClient.worldToRejoin = HotRestoreState.levelIdToRejoin;
            }
            // 如果尚未触发重连，则触发
            if (!MineBackupClient.readyToRejoin && MineBackupClient.worldToRejoin != null) {
                MineBackupClient.readyToRejoin = true;
            }
            HotRestoreState.waitingForServerStopAck = false;
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
                // 使用“完整保存”路径，确保 level.dat 与区块文件同步落盘
                boolean allSaved = saveAllDataForHotBackup(serverInstance);
                if (!allSaved) {
                    LOGGER.warn("One or more levels failed to save during pre_hot_backup for world: {}", worldName);
                    serverInstance.getPlayerManager().broadcast(Text.translatable("minebackup.broadcast.hot_backup.warn", worldName), false);
                }
                LOGGER.info("World saved successfully for hot backup.");
                serverInstance.getPlayerManager().broadcast(Text.translatable("minebackup.broadcast.hot_backup.complete"), false);
                // KnotLink 新协议：通知主程序世界保存已完成
                OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, "WORLD_SAVED");
                LOGGER.info("Sent WORLD_SAVED notification to MineBackup main program.");
            });
        } else if ("game_session_start".equals(eventType)) {
            LOGGER.info("MineBackup detected game session start for world: {}", eventData.getOrDefault("world", "Unknown World"));
        }

        if (message != null) {
            serverInstance.execute(() -> serverInstance.getPlayerManager().broadcast(message, false));
        }
    }

    /**
     * 版本号比较工具：检查当前版本是否满足最低要求
     * 格式为 major.minor.patch（如 "1.0.0"）
     *
     * @param current  当前版本号
     * @param required 要求的最低版本号
     * @return 当前版本 >= 要求版本 时返回 true
     */
    public static boolean isVersionCompatible(String current, String required) {
        if (required == null || required.isBlank()) return true;
        if (current == null || current.isBlank()) return false;
        try {
            int[] c = parseVersionParts(current);
            int[] r = parseVersionParts(required);
            for (int i = 0; i < 3; i++) {
                if (c[i] > r[i]) return true;
                if (c[i] < r[i]) return false;
            }
            return true; // 版本相等
        } catch (Exception e) {
            LOGGER.warn("版本号解析失败: current={}, required={}", current, required);
            return false;
        }
    }

    private static int[] parseVersionParts(String version) {
        String[] parts = version.split("\\.");
        int[] result = new int[3];
        for (int i = 0; i < Math.min(parts.length, 3); i++) {
            result[i] = Integer.parseInt(parts[i].trim());
        }
        return result;
    }

}