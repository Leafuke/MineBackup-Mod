package com.leafuke.minebackup;

import com.mojang.authlib.GameProfile;
import com.leafuke.minebackup.knotlink.OpenSocketQuerier;
import com.leafuke.minebackup.knotlink.SignalSubscriber;
import com.leafuke.minebackup.restore.HotRestoreState;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
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
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Mod(MineBackup.MOD_ID)
public class MineBackup {
    public static final String MOD_ID = "minebackup";
    public static final String PLUGIN_GUIDE_URL = "https://modrinth.com/plugin/minebackupplugin";
    public static final String MOD_VERSION = "2.0.0";
    private static final String MIN_MAIN_PROGRAM_VERSION = "1.14.0";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final String BROADCAST_APP_ID = "0x00000020";
    public static final String BROADCAST_SIGNAL_ID = "0x00000020";
    private static final String QUERIER_APP_ID = "0x00000020";
    private static final String QUERIER_SOCKET_ID = "0x00000010";
    private static final long INTEGRATED_RESTORE_ACK_POLL_MS = 100L;
    private static final long INTEGRATED_RESTORE_ACK_TIMEOUT_MS = 10_000L;

    private static SignalSubscriber knotLinkSubscriber;
    private static volatile MinecraftServer serverInstance;
    private static volatile String lastHandshakeSuccessVersion;

    private static volatile boolean saveFrozen = false;
    private static final List<ServerLevel> frozenWorlds = new ArrayList<>();
    private static volatile long freezeTimestamp = 0L;

    public MineBackup(IEventBus modEventBus, Dist dist) {
        NeoForge.EVENT_BUS.register(this);
        if (dist.isClient()) {
            MineBackupClient.initialize();
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        Command.register(event.getDispatcher(), event.getBuildContext(), event.getCommandSelection());
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        serverInstance = event.getServer();
        lastHandshakeSuccessVersion = null;

        if (event.getServer().isDedicatedServer()) {
            LOGGER.info("MineBackup mod is running on a dedicated server with limited support. Restore workflows remain delegated to the plugin.");
        }

        if (knotLinkSubscriber == null) {
            knotLinkSubscriber = new SignalSubscriber(BROADCAST_APP_ID, BROADCAST_SIGNAL_ID);
            knotLinkSubscriber.setSignalListener(this::handleBroadcastEvent);
            new Thread(knotLinkSubscriber::start).start();
        } else {
            knotLinkSubscriber.setSignalListener(this::handleBroadcastEvent);
        }

        Config.load();
        if (Config.hasAutoBackup()) {
            String cmd = String.format("AUTO_BACKUP %s %d %d", Config.getConfigId(), Config.getWorldIndex(), Config.getInternalTime());
            OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, cmd);
            LOGGER.info("Sent auto backup request from config: {}", cmd);
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (saveFrozen) {
            LOGGER.warn("Server stopping while auto-save is frozen. Unfreezing now.");
            unfreezeAutoSave(event.getServer());
        }
        // 不关闭KnotLink服务，因为等下还有可能rejoin
        // 专用服务器的话需要关一下避免stop没法停止。
        if (event.getServer().isDedicatedServer()) {
            if (knotLinkSubscriber != null) {
                knotLinkSubscriber.stop();
                knotLinkSubscriber = null;
                LOGGER.info("MineBackup Mod: Server stopping, stopped KnotLink Subscriber.");
            }
        }
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        checkFreezeTimeout();
    }

    public static void freezeAutoSave(MinecraftServer server) {
        if (saveFrozen) {
            LOGGER.warn("Auto-save already frozen, skipping.");
            return;
        }
        synchronized (frozenWorlds) {
            frozenWorlds.clear();
            for (ServerLevel level : server.getAllLevels()) {
                if (level != null && !level.noSave) {
                    level.noSave = true;
                    frozenWorlds.add(level);
                }
            }
        }
        saveFrozen = true;
        freezeTimestamp = System.currentTimeMillis();
        LOGGER.info("Auto-save frozen for {} dimensions.", frozenWorlds.size());
    }

    public static void unfreezeAutoSave(MinecraftServer server) {
        if (!saveFrozen) {
            LOGGER.warn("Auto-save is not frozen, skipping.");
            return;
        }
        synchronized (frozenWorlds) {
            for (ServerLevel level : frozenWorlds) {
                if (level != null && level.noSave) {
                    level.noSave = false;
                }
            }
            frozenWorlds.clear();
        }
        saveFrozen = false;
        freezeTimestamp = 0L;
        LOGGER.info("Auto-save resumed.");
    }

    public static boolean isSaveFrozen() {
        return saveFrozen;
    }

    public static boolean isVersionCompatible(String current, String required) {
        if (required == null || required.isBlank()) {
            return true;
        }
        if (current == null || current.isBlank()) {
            return false;
        }
        try {
            int[] currentParts = parseVersionParts(current);
            int[] requiredParts = parseVersionParts(required);
            for (int i = 0; i < 3; i++) {
                if (currentParts[i] > requiredParts[i]) {
                    return true;
                }
                if (currentParts[i] < requiredParts[i]) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            LOGGER.warn("Failed to parse version numbers: current={}, required={}", current, required);
            return false;
        }
    }

    private Map<String, String> parsePayload(String payload) {
        Map<String, String> dataMap = new HashMap<>();
        if (payload == null || payload.isEmpty()) {
            return dataMap;
        }
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

    private String resolveLevelFolder(MinecraftServer server) {
        String levelIdFromPath = null;
        try {
            Path root = server.getWorldPath(LevelResource.ROOT);
            levelIdFromPath = resolveLevelIdFromPath(root);
        } catch (Exception ignored) {
        }

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
        if (normalized.isBlank() || ".".equals(normalized) || "..".equals(normalized)) {
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

    private void handleBroadcastEvent(String payload) {
        if (serverInstance == null) {
            return;
        }

        if ("minebackup save".equals(payload)) {
            serverInstance.execute(() -> {
                serverInstance.getPlayerList().broadcastSystemMessage(Component.translatable("minebackup.message.remote_save.start"), false);
                boolean saved = LocalSaveCoordinator.saveForLocalCommand(serverInstance);
                serverInstance.getPlayerList().broadcastSystemMessage(
                        Component.translatable(saved ? "minebackup.message.remote_save.success" : "minebackup.message.remote_save.fail"),
                        false);
            });
            return;
        }

        Map<String, String> eventData = parsePayload(payload);
        String eventType = eventData.get("event");
        if (eventType == null) {
            return;
        }

        if (serverInstance.isDedicatedServer() && !isDedicatedEventAllowed(eventType)) {
            handleIgnoredDedicatedEvent(eventType);
            return;
        }

        if ("handshake".equals(eventType)) {
            handleHandshake(eventData);
            return;
        }
        if ("pre_hot_restore".equals(eventType)) {
            handlePreHotRestore(eventData);
            return;
        }
        if ("restore_finished".equals(eventType) || "restore_success".equals(eventType)) {
            handleRestoreFinished(eventData, eventType);
            return;
        }
        if ("rejoin_world".equals(eventType)) {
            handleRejoinWorld(eventData);
            return;
        }

        if ("pre_hot_backup".equals(eventType)) {
            serverInstance.execute(() -> {
                String worldName = serverInstance.getWorldData().getLevelName();
                serverInstance.getPlayerList().broadcastSystemMessage(Component.translatable("minebackup.broadcast.hot_backup.request", worldName), false);
                boolean allSaved = LocalSaveCoordinator.saveForLocalCommand(serverInstance);
                if (!allSaved) {
                    serverInstance.getPlayerList().broadcastSystemMessage(Component.translatable("minebackup.broadcast.hot_backup.warn", worldName), false);
                }
                freezeAutoSave(serverInstance);
                serverInstance.getPlayerList().broadcastSystemMessage(Component.translatable("minebackup.broadcast.hot_backup.complete"), false);
                OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, "WORLD_SAVED");
            });
        }

        if (("backup_success".equals(eventType) || "backup_failed".equals(eventType)) && saveFrozen) {
            serverInstance.execute(() -> {
                unfreezeAutoSave(serverInstance);
                serverInstance.getPlayerList().broadcastSystemMessage(Component.translatable("minebackup.broadcast.autosave.resumed"), false);
            });
        }

        Component message = switch (eventType) {
            case "backup_started" -> Component.translatable("minebackup.broadcast.backup.started", getWorldDisplay(eventData));
            case "restore_started" -> Component.translatable("minebackup.broadcast.restore.started", getWorldDisplay(eventData));
            case "backup_success" -> Component.translatable("minebackup.broadcast.backup.success", getWorldDisplay(eventData), getFileDisplay(eventData));
            case "backup_failed" -> Component.translatable("minebackup.broadcast.backup.failed", getWorldDisplay(eventData), getErrorDisplay(eventData));
            case "game_session_end" -> Component.translatable("minebackup.broadcast.session.end", getWorldDisplay(eventData));
            case "auto_backup_started" -> Component.translatable("minebackup.broadcast.auto_backup.started", getWorldDisplay(eventData));
            case "we_snapshot_completed" -> Component.translatable("minebackup.broadcast.we_snapshot.completed", getWorldDisplay(eventData), getFileDisplay(eventData));
            default -> null;
        };

        if (message != null) {
            serverInstance.execute(() -> serverInstance.getPlayerList().broadcastSystemMessage(message, false));
        }
    }

    private boolean isDedicatedEventAllowed(String eventType) {
        return switch (eventType) {
            case "handshake", "pre_hot_backup", "backup_started", "backup_success", "backup_failed",
                    "auto_backup_started", "we_snapshot_completed", "game_session_end" -> true;
            default -> false;
        };
    }

    private void handleIgnoredDedicatedEvent(String eventType) {
        switch (eventType) {
            case "pre_hot_restore" -> {
                LOGGER.warn("Ignoring unsupported dedicated-server restore event: {}", eventType);
                broadcastDedicatedRestoreUnsupported();
            }
            case "restore_started", "restore_finished", "restore_success", "rejoin_world" ->
                    LOGGER.warn("Ignoring unsupported dedicated-server restore event: {}", eventType);
            default -> LOGGER.info("Ignoring unsupported backend event on dedicated server: {}", eventType);
        }
    }

    private void broadcastDedicatedRestoreUnsupported() {
        if (serverInstance == null) {
            return;
        }
        serverInstance.execute(() -> {
            serverInstance.getPlayerList().broadcastSystemMessage(
                    Component.translatable("minebackup.message.restore.unsupported_dedicated"), false);
            serverInstance.getPlayerList().broadcastSystemMessage(buildPluginLinkMessage(), false);
        });
    }

    private Component buildPluginLinkMessage() {
        return Component.translatable("minebackup.message.plugin_link_prefix")
                .append(Component.literal(PLUGIN_GUIDE_URL).withStyle(style -> style
                .withClickEvent(new net.minecraft.network.chat.ClickEvent(
                    net.minecraft.network.chat.ClickEvent.Action.OPEN_URL, PLUGIN_GUIDE_URL))
                .withHoverEvent(new net.minecraft.network.chat.HoverEvent(
                    net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT,
                    Component.translatable("minebackup.message.plugin_link_hover")))
                        .withUnderlined(true)));
    }

    private void handleHandshake(Map<String, String> eventData) {
        String mainVersion = eventData.get("version");
        String minModVersion = eventData.get("min_mod_version");
        String displayMainVersion = mainVersion != null ? mainVersion : "?";

        HotRestoreState.mainProgramVersion = mainVersion;
        HotRestoreState.handshakeCompleted = true;
        HotRestoreState.requiredMinModVersion = minModVersion;
        HotRestoreState.versionCompatible = isVersionCompatible(MOD_VERSION, minModVersion);

        OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, "HANDSHAKE_RESPONSE " + MOD_VERSION);

        if (!isVersionCompatible(mainVersion, MIN_MAIN_PROGRAM_VERSION)) {
            serverInstance.execute(() -> serverInstance.getPlayerList().broadcastSystemMessage(
                    Component.translatable("minebackup.message.handshake.main_version_incompatible",
                            displayMainVersion, MIN_MAIN_PROGRAM_VERSION),
                    false));
            return;
        }

        if (!HotRestoreState.versionCompatible) {
            serverInstance.execute(() -> serverInstance.getPlayerList().broadcastSystemMessage(
                    Component.translatable("minebackup.message.handshake.version_incompatible",
                            MOD_VERSION, minModVersion != null ? minModVersion : "?"),
                    false));
            return;
        }

        if (shouldBroadcastHandshakeSuccess(displayMainVersion)) {
            serverInstance.execute(() -> serverInstance.getPlayerList().broadcastSystemMessage(
                    Component.translatable("minebackup.message.handshake.success", displayMainVersion),
                    false));
        }
    }

    private void handlePreHotRestore(Map<String, String> eventData) {
        serverInstance.execute(() -> {
            if (HotRestoreState.isRestoring) {
                LOGGER.warn("Ignored duplicate pre_hot_restore signal while restore is still running.");
                return;
            }

            serverInstance.getPlayerList().broadcastSystemMessage(Component.translatable("minebackup.message.restore.preparing"), false);
            HotRestoreState.isRestoring = true;
            HotRestoreState.waitingForServerStopAck = true;

            if (serverInstance.isDedicatedServer()) {
                LocalSaveCoordinator.saveForLocalCommand(serverInstance);
                Component kickMessage = Component.translatable("minebackup.message.restore.kick");
                disconnectPlayersForRestore(kickMessage, false);
                new Thread(() -> {
                    sleepQuietly(500L);
                    sendWorldSaveAndExitAck();
                }).start();
                serverInstance.stopServer();
                return;
            }

            String levelId = resolveRejoinLevelId(serverInstance, eventData.get("world"));
            MineBackupClient.setWorldToRejoin(levelId);
            HotRestoreState.levelIdToRejoin = levelId;
            LocalSaveCoordinator.saveForLocalCommand(serverInstance);

            Component kickMessage = Component.translatable("minebackup.message.restore.kick");
            disconnectPlayersForRestore(kickMessage, true);
            startIntegratedRestoreAckWatcher(serverInstance);
        });
    }

    private void handleRestoreFinished(Map<String, String> eventData, String eventType) {
        String status = "restore_success".equals(eventType) ? "success" : eventData.getOrDefault("status", "success");
        if (!"success".equals(status)) {
            MineBackupClient.clearReadyToRejoin();
            MineBackupClient.resetRestoreState();
            return;
        }

        String worldFromEvent = eventData.get("world");
        if (isValidLevelId(worldFromEvent)) {
            String fallbackLevelId = worldFromEvent.trim();
            if (!isValidLevelId(HotRestoreState.levelIdToRejoin)) {
                HotRestoreState.levelIdToRejoin = fallbackLevelId;
            }
            if (!isValidLevelId(MineBackupClient.getWorldToRejoin())) {
                MineBackupClient.setWorldToRejoin(fallbackLevelId);
            }
        }

        MineBackupClient.clearReadyToRejoin();
        if (HotRestoreState.levelIdToRejoin != null && MineBackupClient.getWorldToRejoin() == null) {
            MineBackupClient.setWorldToRejoin(HotRestoreState.levelIdToRejoin);
        }
        HotRestoreState.waitingForServerStopAck = false;
        MineBackupClient.showRestoreSuccessOverlay();
    }

    private void handleRejoinWorld(Map<String, String> eventData) {
        String worldFromEvent = eventData.get("world");
        if (isValidLevelId(worldFromEvent)) {
            String fallbackLevelId = worldFromEvent.trim();
            if (!isValidLevelId(HotRestoreState.levelIdToRejoin)) {
                HotRestoreState.levelIdToRejoin = fallbackLevelId;
            }
            if (!isValidLevelId(MineBackupClient.getWorldToRejoin())) {
                MineBackupClient.setWorldToRejoin(fallbackLevelId);
            }
        }

        if (HotRestoreState.levelIdToRejoin != null && MineBackupClient.getWorldToRejoin() == null) {
            MineBackupClient.setWorldToRejoin(HotRestoreState.levelIdToRejoin);
        }

        if (!MineBackupClient.isReadyToRejoin()) {
            if (isValidLevelId(MineBackupClient.getWorldToRejoin())) {
                MineBackupClient.markReadyToRejoin();
            } else {
                OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, "REJOIN_RESULT failure invalid_level_id");
            }
        }
        HotRestoreState.waitingForServerStopAck = false;
    }

    private void disconnectPlayersForRestore(Component kickMessage, boolean hostLast) {
        ServerPlayer[] players = serverInstance.getPlayerList().getPlayers().toArray(new ServerPlayer[0]);
        if (!hostLast) {
            for (ServerPlayer player : players) {
                disconnectPlayer(player, kickMessage);
            }
            return;
        }

        for (ServerPlayer player : players) {
            if (!isSingleplayerHost(player)) {
                disconnectPlayer(player, kickMessage);
            }
        }
        for (ServerPlayer player : players) {
            if (isSingleplayerHost(player)) {
                disconnectPlayer(player, kickMessage);
            }
        }
    }

    private void disconnectPlayer(ServerPlayer player, Component kickMessage) {
        try {
            player.connection.disconnect(kickMessage);
        } catch (Exception e) {
            LOGGER.warn("Failed to disconnect player '{}': {}", player.getGameProfile().getName(), e.getMessage());
        }
    }

    private boolean isSingleplayerHost(ServerPlayer player) {
        if (player == null || serverInstance == null) {
            return false;
        }
        try {
            GameProfile profile = player.getGameProfile();
            return profile != null && serverInstance.isSingleplayerOwner(profile);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void startIntegratedRestoreAckWatcher(MinecraftServer restoreServer) {
        new Thread(() -> {
            long startAt = System.currentTimeMillis();
            while (true) {
                if (isIntegratedRestoreReadyForAck(restoreServer)) {
                    sendWorldSaveAndExitAck();
                    return;
                }

                long elapsed = System.currentTimeMillis() - startAt;
                if (elapsed >= INTEGRATED_RESTORE_ACK_TIMEOUT_MS) {
                    LOGGER.warn("Timed out waiting integrated server release for restore ACK after {}ms.", elapsed);
                    sendWorldSaveAndExitAck();
                    return;
                }

                sleepQuietly(INTEGRATED_RESTORE_ACK_POLL_MS);
            }
        }, "MineBackup-RestoreAckWaiter").start();
    }

    private boolean isIntegratedRestoreReadyForAck(MinecraftServer restoreServer) {
        if (restoreServer == null) {
            return true;
        }

        try {
            if (!restoreServer.getPlayerList().getPlayers().isEmpty()) {
                return false;
            }
        } catch (Exception e) {
            return false;
        }

        return canAcquireSessionLock(restoreServer);
    }

    private boolean canAcquireSessionLock(MinecraftServer restoreServer) {
        try {
            Path root = restoreServer.getWorldPath(LevelResource.ROOT);
            if (root == null) {
                return true;
            }

            Path lockPath = root.resolve("session.lock");
            if (!Files.exists(lockPath)) {
                return true;
            }

            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.WRITE)) {
                try (FileLock lock = channel.tryLock()) {
                    return lock != null;
                } catch (OverlappingFileLockException e) {
                    return false;
                }
            }
        } catch (Exception e) {
            return false;
        }
    }

    private void sendWorldSaveAndExitAck() {
        OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, "WORLD_SAVE_AND_EXIT_COMPLETE");
    }

    private void checkFreezeTimeout() {
        if (!saveFrozen || serverInstance == null || !FreezeWatchdog.hasTimedOut(freezeTimestamp)) {
            return;
        }

        long elapsed = FreezeWatchdog.elapsedSince(freezeTimestamp);
        LOGGER.error("Auto-save freeze timed out after {}ms, forcing resume.", elapsed);
        unfreezeAutoSave(serverInstance);
        serverInstance.getPlayerList().broadcastSystemMessage(Component.translatable("minebackup.broadcast.autosave.timeout"), false);
    }

    private static boolean shouldBroadcastHandshakeSuccess(String mainVersion) {
        synchronized (MineBackup.class) {
            if (mainVersion.equals(lastHandshakeSuccessVersion)) {
                return false;
            }
            lastHandshakeSuccessVersion = mainVersion;
            return true;
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

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
