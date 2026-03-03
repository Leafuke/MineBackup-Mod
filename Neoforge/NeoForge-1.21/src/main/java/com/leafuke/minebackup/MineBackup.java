package com.leafuke.minebackup;

import com.leafuke.minebackup.knotlink.OpenSocketQuerier;
import com.leafuke.minebackup.knotlink.SignalSubscriber;
import com.leafuke.minebackup.restore.HotRestoreState;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.server.level.ServerLevel;

// 使用 @Mod 注解，这是 NeoForge 的入口点
@Mod(MineBackup.MOD_ID)
public class MineBackup {

    public static final String MOD_ID = "minebackup";
    public static final String MOD_VERSION = "1.1.0";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static SignalSubscriber knotLinkSubscriber = null;
    private static volatile MinecraftServer serverInstance;

    private static volatile boolean saveFrozen = false;
    private static final List<ServerLevel> frozenWorlds = new ArrayList<>();
    private static volatile long freezeTimestamp = 0;
    private static final long FREEZE_TIMEOUT_MS = 3 * 60 * 1000L;  // 3min

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
        } else {
            LOGGER.info("MineBackup Mod: Server is starting, KnotLink Subscriber has been here...");
            serverInstance = event.getServer();
            knotLinkSubscriber.setSignalListener(this::handleBroadcastEvent);
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
        if (saveFrozen) {
            LOGGER.warn("[Safety] Server stopping while auto-save is frozen! Unfreezing now.");
            unfreezeAutoSave(event.getServer());
        }

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

    /**
     * 尝试解析当前世界存档文件夹名，优先使用根路径文件夹名称。
     */
    private String resolveLevelFolder(MinecraftServer server) {
        String levelIdFromPath = null;
        try {
            Path root = server.getWorldPath(LevelResource.ROOT);
            levelIdFromPath = resolveLevelIdFromPath(root);
        } catch (Exception ignored) { }

        if (isValidLevelId(levelIdFromPath)) {
            return levelIdFromPath;
        }

        String levelName = server.getWorldData().getLevelName();
        if (isValidLevelId(levelName)) {
            return levelName;
        }
        return "world";
    }

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

    private Boolean invokeServerSaveMethod(MinecraftServer server, String methodName) {
        try {
            java.lang.reflect.Method method = server.getClass().getMethod(
                    methodName, boolean.class, boolean.class, boolean.class);
            Object result = method.invoke(server, true, true, true);
            if (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class) {
                return Boolean.TRUE.equals(result);
            }
            return true;
        } catch (NoSuchMethodException ignored) {
            return null;
        } catch (Throwable t) {
            LOGGER.warn("调用 {} 进行完整保存时失败: {}", methodName, t.getMessage());
            return null;
        }
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

    private void handleBroadcastEvent(String payload) {
        if (serverInstance == null) return;
        checkFreezeTimeout();

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

        if ("handshake".equals(eventType)) {
            String mainVersion = eventData.get("version");
            String action = eventData.get("action");
            String world = eventData.get("world");
            String minModVersion = eventData.get("min_mod_version");

            LOGGER.info("Received handshake from MineBackup v{}, action={}, world={}, min_mod_version={}",
                    mainVersion, action, world, minModVersion);

            HotRestoreState.mainProgramVersion = mainVersion;
            HotRestoreState.handshakeCompleted = true;
            HotRestoreState.requiredMinModVersion = minModVersion;

            boolean compatible = isVersionCompatible(MOD_VERSION, minModVersion);
            HotRestoreState.versionCompatible = compatible;

            OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, "HANDSHAKE_RESPONSE " + MOD_VERSION);
            LOGGER.info("Sent HANDSHAKE_RESPONSE with mod version {}", MOD_VERSION);

            if (!isVersionCompatible(mainVersion, "1.13.0")) {
                serverInstance.execute(() -> serverInstance.getPlayerList().broadcastSystemMessage(
                        Component.translatable("minebackup.message.handshake.main_version_incompatible",
                                mainVersion, "1.13.0"), false));
                return;
            }

            if (!compatible) {
                try {
                    serverInstance.execute(() -> serverInstance.getPlayerList().broadcastSystemMessage(
                            Component.translatable("minebackup.message.handshake.version_incompatible",
                                    MOD_VERSION, minModVersion != null ? minModVersion : "?"), false));
                } catch (Exception ignored) { }
                LOGGER.warn("Mod version {} does not meet minimum requirement {}", MOD_VERSION, minModVersion);
            } else {
                try {
                    serverInstance.execute(() -> serverInstance.getPlayerList().broadcastSystemMessage(
                            Component.translatable("minebackup.message.handshake.success",
                                    mainVersion != null ? mainVersion : "?"), false));
                } catch (Exception ignored) { }
            }
            return;
        }

        if ("pre_hot_restore".equals(eventType)) {
            LOGGER.info("Received 'pre_hot_restore'. Preparing for hot restore.");
            serverInstance.execute(() -> {
                serverInstance.getPlayerList().broadcastSystemMessage(Component.translatable("minebackup.message.restore.preparing"), false);

                HotRestoreState.isRestoring = true;
                HotRestoreState.waitingForServerStopAck = true;

                if (serverInstance.isDedicatedServer()) {
                    LOGGER.info("Dedicated server detected. Saving, kicking players, then stopping.");

                    boolean saveSuccess = serverInstance.saveAllChunks(true, true, true);
                    if (!saveSuccess) {
                        LOGGER.warn("World save may be incomplete before hot restore on dedicated server.");
                    }

                    var playerList = serverInstance.getPlayerList().getPlayers();
                    Component kickMessage = Component.translatable("minebackup.message.restore.kick");
                    for (var player : playerList.toArray(new ServerPlayer[0])) {
                        try {
                            player.connection.disconnect(kickMessage);
                        } catch (Exception e) {
                            LOGGER.warn("Failed to disconnect player {}", e.getMessage());
                        }
                    }

                    new Thread(() -> {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                        OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, "WORLD_SAVE_AND_EXIT_COMPLETE");
                        LOGGER.info("Sent WORLD_SAVE_AND_EXIT_COMPLETE to MineBackup main program (dedicated).");
                    }).start();

                    serverInstance.stopServer();
                } else {
                    LOGGER.info("Single-player instance detected. Saving and disconnecting player.");

                    String levelId = resolveRejoinLevelId(serverInstance, eventData.get("world"));
                    MineBackupClient.worldToRejoin = levelId;
                    HotRestoreState.levelIdToRejoin = levelId;
                    LOGGER.info("Captured level folder for auto rejoin: {}", levelId);

                    boolean saveSuccess = serverInstance.saveAllChunks(true, true, true);
                    if (!saveSuccess) {
                        LOGGER.warn("World save may be incomplete before hot restore on singleplayer.");
                    }

                    var players = serverInstance.getPlayerList().getPlayers();
                    Component kickMessage = Component.translatable("minebackup.message.restore.kick");
                    for (var player : players.toArray(new ServerPlayer[0])) {
                        try {
                            player.connection.disconnect(kickMessage);
                        } catch (Exception e) {
                            LOGGER.warn("Failed to disconnect player {}", e.getMessage());
                        }
                    }

                    new Thread(() -> {
                        try {
                            Thread.sleep(400);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                        OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, "WORLD_SAVE_AND_EXIT_COMPLETE");
                        LOGGER.info("Sent WORLD_SAVE_AND_EXIT_COMPLETE to MineBackup main program (singleplayer).");
                    }).start();
                }
            });
            return;
        }

        if ("restore_finished".equals(eventType) || "restore_success".equals(eventType)) {
            String status = "restore_success".equals(eventType) ? "success" : eventData.getOrDefault("status", "success");
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
                MineBackupClient.readyToRejoin = false;
                if (HotRestoreState.levelIdToRejoin != null && MineBackupClient.worldToRejoin == null) {
                    MineBackupClient.worldToRejoin = HotRestoreState.levelIdToRejoin;
                }
                HotRestoreState.waitingForServerStopAck = false;
                MineBackupClient.showRestoreSuccessOverlay();
                LOGGER.info("还原成功，等待 rejoin_world 事件后再执行重连");
            } else {
                LOGGER.warn("主程序报告还原失败，status={}", status);
                MineBackupClient.readyToRejoin = false;
                HotRestoreState.reset();
            }
            return;
        }

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
            if (HotRestoreState.levelIdToRejoin != null && MineBackupClient.worldToRejoin == null) {
                MineBackupClient.worldToRejoin = HotRestoreState.levelIdToRejoin;
            }
            if (!MineBackupClient.readyToRejoin) {
                if (isValidLevelId(MineBackupClient.worldToRejoin)) {
                    MineBackupClient.readyToRejoin = true;
                } else {
                    LOGGER.warn("rejoin_world 事件缺少有效 world，发送 REJOIN_RESULT failure invalid_level_id");
                    OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, "REJOIN_RESULT failure invalid_level_id");
                }
            }
            HotRestoreState.waitingForServerStopAck = false;
            return;
        }

        final Component message = switch (eventType) {
            case "backup_started" -> Component.translatable("minebackup.broadcast.backup.started", getWorldDisplay(eventData));
            case "restore_started" -> Component.translatable("minebackup.broadcast.restore.started", getWorldDisplay(eventData));
            case "backup_success" -> Component.translatable("minebackup.broadcast.backup.success", getWorldDisplay(eventData), getFileDisplay(eventData));
            case "backup_failed" -> Component.translatable("minebackup.broadcast.backup.failed", getWorldDisplay(eventData), getErrorDisplay(eventData));
            case "game_session_end" -> Component.translatable("minebackup.broadcast.session.end", getWorldDisplay(eventData));
            case "auto_backup_started" -> Component.translatable("minebackup.broadcast.auto_backup.started", getWorldDisplay(eventData));
            case "we_snapshot_completed" -> Component.translatable("minebackup.broadcast.we_snapshot.completed", getWorldDisplay(eventData), getFileDisplay(eventData));
            default -> null;
        };

        if ("pre_hot_backup".equals(eventType)) {
            serverInstance.execute(() -> {
                LOGGER.info("Executing immediate save for pre_hot_backup event.");
                String worldName = serverInstance.getWorldData().getLevelName();
                serverInstance.getPlayerList().broadcastSystemMessage(Component.translatable("minebackup.broadcast.hot_backup.request", worldName), false);
                boolean allSaved = saveAllDataForHotBackup(serverInstance);
                if (!allSaved) {
                    LOGGER.warn("One or more levels failed to save during pre_hot_backup for world: {}", worldName);
                    serverInstance.getPlayerList().broadcastSystemMessage(Component.translatable("minebackup.broadcast.hot_backup.warn", worldName), false);
                }
                freezeAutoSave(serverInstance);

                LOGGER.info("World saved and auto-save frozen for hot backup.");
                serverInstance.getPlayerList().broadcastSystemMessage(Component.translatable("minebackup.broadcast.hot_backup.complete"), false);
                OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, "WORLD_SAVED");
                LOGGER.info("Sent WORLD_SAVED notification to MineBackup main program.");
            });
        } else if ("game_session_start".equals(eventType)) {
            LOGGER.info("MineBackup detected game session start for world: {}", getWorldDisplay(eventData).getString());
        }

        // 备份完成后恢复自动保存
        if ("backup_success".equals(eventType) || "backup_failed".equals(eventType)) {
            if (saveFrozen) {
                serverInstance.execute(() -> {
                    unfreezeAutoSave(serverInstance);
                    LOGGER.info("Backup finished (event={}), auto-save unfrozen.", eventType);
                    serverInstance.getPlayerList().broadcastSystemMessage(
                            Component.translatable("minebackup.broadcast.autosave.resumed"), false);
                });
            }
        }

        if (message != null) {
            serverInstance.execute(() -> serverInstance.getPlayerList().broadcastSystemMessage(message, false));
        }
    }

    public static void freezeAutoSave(MinecraftServer server) {
        if (saveFrozen) {
            LOGGER.warn("[Freeze] Auto-save already frozen, skipping.");
            return;
        }
        synchronized (frozenWorlds) {
            frozenWorlds.clear();
            for (ServerLevel level : server.getAllLevels()) {
                if (level != null && !level.noSave) {
                    level.noSave = true;
                    frozenWorlds.add(level);
                    LOGGER.info("[Freeze] Disabled auto-save for dimension: {}",
                            level.dimension().location());
                }
            }
        }
        saveFrozen = true;
        freezeTimestamp = System.currentTimeMillis();
        LOGGER.info("[Freeze] Auto-save frozen for {} dimensions.", frozenWorlds.size());
    }

    public static void unfreezeAutoSave(MinecraftServer server) {
        if (!saveFrozen) {
            LOGGER.warn("[Unfreeze] Auto-save not frozen, skipping.");
            return;
        }
        synchronized (frozenWorlds) {
            for (ServerLevel level : frozenWorlds) {
                if (level != null && level.noSave) {
                    level.noSave = false;
                    LOGGER.info("[Unfreeze] Re-enabled auto-save for dimension: {}",
                            level.dimension().location());
                }
            }
            frozenWorlds.clear();
        }
        saveFrozen = false;
        freezeTimestamp = 0;
        LOGGER.info("[Unfreeze] Auto-save unfrozen.");
    }

    private void checkFreezeTimeout() {
        if (saveFrozen && freezeTimestamp > 0) {
            long elapsed = System.currentTimeMillis() - freezeTimestamp;
            if (elapsed > FREEZE_TIMEOUT_MS) {
                LOGGER.error("[Safety] Auto-save freeze timed out after {}ms! Force unfreezing.", elapsed);
                if (serverInstance != null) {
                    unfreezeAutoSave(serverInstance);
                    serverInstance.getPlayerList().broadcastSystemMessage(
                            Component.translatable("minebackup.broadcast.autosave.timeout"), false);
                }
            }
        }
    }

    public static boolean isSaveFrozen() {
        return saveFrozen;
    }

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
            return true;
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

