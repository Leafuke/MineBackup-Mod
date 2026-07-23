package com.leafuke.minebackup;

import com.leafuke.minebackup.knotlink.KnotLinkClient;
import com.leafuke.minebackup.knotlink.KnotLinkRequest;
import com.leafuke.minebackup.restore.RestoreSession;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class MineBackupRuntime implements AutoCloseable {
    private static final String MINIMUM_MAIN_VERSION = "1.14.0";
    private static final long RELEASE_TIMEOUT_NANOS = Duration.ofSeconds(8).toNanos();
    private static final int READY_STREAK_REQUIRED = 3;

    private final KnotLinkClient knotLink = new KnotLinkClient();
    private final RestoreSession restoreSession = new RestoreSession();
    private final AutoSaveController autoSave = new AutoSaveController();
    private final ScheduledExecutorService coordinator = Executors.newSingleThreadScheduledExecutor(
            daemonThreadFactory());

    private volatile MinecraftServer server;
    private volatile boolean dedicatedServer;
    private volatile String lastHandshakeNoticeVersion;
    private volatile ReleasePoller releasePoller;

    public void registerEvents() {
        ServerLifecycleEvents.SERVER_STARTING.register(this::onServerStarting);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
        ServerLifecycleEvents.SERVER_STOPPED.register(this::onServerStopped);
        ServerTickEvents.END_SERVER_TICK.register(autoSave::tick);
    }

    public KnotLinkClient knotLink() {
        return knotLink;
    }

    public void completeClientRestore() {
        restoreSession.reset();
    }

    private void onServerStarting(MinecraftServer startingServer) {
        server = startingServer;
        dedicatedServer = startingServer.isDedicatedServer();
        lastHandshakeNoticeVersion = null;
        Config.Snapshot config = Config.load();
        knotLink.startSubscriber(this::handleSignal);

        if (dedicatedServer) {
            MineBackup.LOGGER.info(
                    "MineBackup 3 is running on a dedicated server. Restore commands remain disabled.");
        }

        Config.AutoBackup autoBackup = config.autoBackup();
        if (autoBackup != null) {
            KnotLinkRequest request = KnotLinkRequest.command("AUTO_BACKUP")
                    .conversation()
                    .field("config_id", autoBackup.configId())
                    .field("folder", autoBackup.folder())
                    .field("interval_minutes", autoBackup.intervalMinutes());
            knotLink.query(request).whenComplete((response, error) -> {
                if (error != null) {
                    MineBackup.LOGGER.warn("Failed to restore automatic backup schedule", error);
                } else if (!response.isOk()) {
                    MineBackup.LOGGER.warn(
                            "Backend rejected persisted automatic backup schedule: {}",
                            response.displayMessage());
                } else {
                    MineBackup.LOGGER.info("Restored persisted automatic backup schedule.");
                }
            });
        }
    }

    private void onServerStopping(MinecraftServer stoppingServer) {
        if (autoSave.unfreeze()) {
            MineBackup.LOGGER.warn("Server stopped while auto-save was frozen; save state was restored.");
        }
        if (dedicatedServer) {
            close();
        }
    }

    private void onServerStopped(MinecraftServer stoppedServer) {
        ReleasePoller currentPoller = releasePoller;
        if (currentPoller != null && currentPoller.restoreServer == stoppedServer) {
            currentPoller.markServerStopped(
                    stoppedServer.getPlayerList().getPlayers().isEmpty());
        }
        if (server == stoppedServer) {
            server = null;
        }
        if (restoreSession.phase() == RestoreSession.Phase.IDLE) {
            restoreSession.reset();
        }
    }

    private void handleSignal(Map<String, String> fields) {
        String event = fields.get("event");
        if (event == null) {
            return;
        }

        switch (event) {
            case "handshake" -> handleHandshake(fields);
            case "pre_hot_backup" -> handlePreHotBackup(fields);
            case "pre_hot_restore" -> handlePreHotRestore(fields);
            case "restore_finished" -> handleRestoreFinished(fields);
            case "restore_cancelled" -> failRestore(
                    fields,
                    "minebackup.message.restore.cancelled");
            case "rejoin_world" -> handleRejoinWorld(fields);
            case "backup_success", "backup_failed" -> handleBackupTerminal(fields, event);
            default -> broadcastInformationalEvent(fields, event);
        }
    }

    private void handleHandshake(Map<String, String> fields) {
        MinecraftServer currentServer = server;
        if (currentServer == null) {
            MineBackup.LOGGER.debug("Ignoring handshake without an active Minecraft server.");
            return;
        }

        String action = fields.get("action");
        String world = fields.get("world");
        String mainVersion = fields.get("version");
        String minimumModVersion = fields.get("min_mod_version");
        Optional<RestoreSession.Action> parsedAction = RestoreSession.Action.parse(action);
        if (parsedAction.isEmpty()
                || world == null
                || world.isBlank()
                || mainVersion == null
                || mainVersion.isBlank()
                || minimumModVersion == null
                || minimumModVersion.isBlank()) {
            MineBackup.LOGGER.warn("Rejected incomplete KnotLink handshake.");
            return;
        }
        if (parsedAction.get() == RestoreSession.Action.RESTORE && dedicatedServer) {
            currentServer.executeIfPossible(() -> {
                currentServer.getPlayerList().broadcastSystemMessage(
                        Component.translatable("minebackup.message.restore.unsupported_dedicated"),
                        false);
                currentServer.getPlayerList().broadcastSystemMessage(MineBackup.pluginLinkMessage(), false);
            });
            return;
        }
        if (!VersionNumber.isAtLeast(mainVersion, MINIMUM_MAIN_VERSION)) {
            currentServer.executeIfPossible(() -> currentServer.getPlayerList().broadcastSystemMessage(
                    Component.translatable(
                            "minebackup.message.handshake.main_version_incompatible",
                            mainVersion == null ? "?" : mainVersion,
                            MINIMUM_MAIN_VERSION),
                    false));
            return;
        }
        if (!VersionNumber.isAtLeast(ModInfo.version(), minimumModVersion)) {
            currentServer.executeIfPossible(() -> currentServer.getPlayerList().broadcastSystemMessage(
                    Component.translatable(
                            "minebackup.message.handshake.version_incompatible",
                            ModInfo.version(),
                            minimumModVersion == null ? "?" : minimumModVersion),
                    false));
            return;
        }
        if (autoSave.isFrozen()) {
            MineBackup.LOGGER.warn("Rejected KnotLink handshake while a hot backup is active.");
            return;
        }

        long generation = restoreSession.recordHandshake(
                action,
                world,
                mainVersion,
                minimumModVersion);
        if (generation < 0L) {
            MineBackup.LOGGER.warn("Rejected duplicate or out-of-phase KnotLink handshake.");
            return;
        }

        KnotLinkRequest responseRequest = KnotLinkRequest.command("HANDSHAKE_RESPONSE")
                .conversation()
                .field("mod_version", ModInfo.version());
        knotLink.query(responseRequest).whenComplete((response, error) -> {
            if (error != null || !response.isOk()) {
                restoreSession.clearHandshake(generation);
                if (error != null) {
                    MineBackup.LOGGER.warn("Failed to acknowledge KnotLink handshake", error);
                } else {
                    MineBackup.LOGGER.warn("KnotLink handshake was rejected: {}", response.displayMessage());
                }
                return;
            }
            showHandshakeSuccessOnce(currentServer, mainVersion);
        });
    }

    private void handlePreHotBackup(Map<String, String> fields) {
        if (!restoreSession.consumeHandshake(RestoreSession.Action.BACKUP, fields.get("world"))) {
            MineBackup.LOGGER.warn("Rejected pre_hot_backup without a matching, fresh handshake.");
            return;
        }

        MinecraftServer currentServer = server;
        if (currentServer == null) {
            return;
        }
        currentServer.executeIfPossible(() -> {
            if (!LocalSaveCoordinator.save(currentServer)) {
                currentServer.getPlayerList().broadcastSystemMessage(
                        Component.translatable("minebackup.broadcast.hot_backup.save_failed"),
                        false);
                return;
            }
            if (!autoSave.freeze(currentServer)) {
                return;
            }

            knotLink.query(KnotLinkRequest.command("WORLD_SAVED").conversation())
                    .whenComplete((response, error) -> {
                        if (error == null && response.isOk()) {
                            currentServer.executeIfPossible(() ->
                                    currentServer.getPlayerList().broadcastSystemMessage(
                                            Component.translatable("minebackup.broadcast.hot_backup.complete"),
                                            false));
                            return;
                        }
                        if (error != null) {
                            MineBackup.LOGGER.error(
                                    "Failed to acknowledge the saved world to the backend",
                                    error);
                        } else {
                            MineBackup.LOGGER.error(
                                    "Backend rejected the saved-world acknowledgement: {}",
                                    response.displayMessage());
                        }
                        currentServer.executeIfPossible(() -> {
                            autoSave.unfreeze();
                            currentServer.getPlayerList().broadcastSystemMessage(
                                    Component.translatable("minebackup.broadcast.hot_backup.ack_failed"),
                                    false);
                        });
                    });
        });
    }

    private void handleBackupTerminal(Map<String, String> fields, String event) {
        MinecraftServer currentServer = server;
        if (currentServer != null) {
            currentServer.executeIfPossible(() -> {
                if (autoSave.unfreeze()) {
                    currentServer.getPlayerList().broadcastSystemMessage(
                            Component.translatable("minebackup.broadcast.autosave.resumed"),
                            false);
                }
                broadcastInformationalEventOnServer(currentServer, fields, event);
            });
        } else {
            autoSave.unfreeze();
        }
    }

    private void handlePreHotRestore(Map<String, String> fields) {
        if (!restoreSession.consumeHandshake(RestoreSession.Action.RESTORE, fields.get("world"))) {
            MineBackup.LOGGER.warn("Rejected pre_hot_restore without a matching, fresh handshake.");
            return;
        }

        MinecraftServer currentServer = server;
        if (currentServer == null || dedicatedServer) {
            return;
        }
        currentServer.executeIfPossible(() -> beginIntegratedRestore(currentServer));
    }

    private void beginIntegratedRestore(MinecraftServer currentServer) {
        if (restoreSession.phase() != RestoreSession.Phase.IDLE) {
            MineBackup.LOGGER.warn("Ignored duplicate hot restore request.");
            return;
        }

        String levelId;
        try {
            levelId = resolveLevelId(currentServer);
        } catch (IllegalStateException exception) {
            MineBackup.LOGGER.error("Unable to identify the integrated world for hot restore", exception);
            currentServer.getPlayerList().broadcastSystemMessage(
                    Component.translatable("minebackup.message.restore.failed"),
                    false);
            return;
        }
        if (!LocalSaveCoordinator.save(currentServer)) {
            currentServer.getPlayerList().broadcastSystemMessage(
                    Component.translatable("minebackup.message.restore.failed"),
                    false);
            return;
        }

        LanState lanState = captureLanState(currentServer);
        if (!restoreSession.beginRestore(levelId, lanState.reopen(), lanState.port())) {
            return;
        }

        currentServer.getPlayerList().broadcastSystemMessage(
                Component.translatable("minebackup.message.restore.preparing"),
                false);
        disconnectPlayers(currentServer, Component.translatable("minebackup.message.restore.kick"));
        startReleaseWatcher(currentServer);
    }

    private void startReleaseWatcher(MinecraftServer restoreServer) {
        ReleasePoller poller = new ReleasePoller(restoreServer);
        releasePoller = poller;
        poller.future = coordinator.scheduleAtFixedRate(
                poller,
                100L,
                100L,
                TimeUnit.MILLISECONDS);
    }

    private void handleRestoreFinished(Map<String, String> fields) {
        if (!restoreSession.matchesActiveWorld(fields.get("world"))) {
            MineBackup.LOGGER.warn("Rejected restore_finished for an inactive world.");
            return;
        }
        String status = fields.getOrDefault("status", "error");
        if (!"success".equalsIgnoreCase(status)) {
            failRestore("minebackup.message.restore.failed_status");
            return;
        }
        if (!restoreSession.markRestoreSucceeded()) {
            MineBackup.LOGGER.warn("Rejected restore_finished outside an active restore session.");
        }
    }

    private void handleRejoinWorld(Map<String, String> fields) {
        if (!restoreSession.matchesActiveWorld(fields.get("world"))) {
            MineBackup.LOGGER.warn("Rejected rejoin_world for an inactive world.");
            return;
        }
        Optional<RestoreSession.RejoinInfo> rejoin = restoreSession.beginRejoin();
        if (rejoin.isEmpty()) {
            MineBackup.LOGGER.warn("Rejected rejoin_world outside the expected restore phase.");
            return;
        }
        ClientHooks.requestRejoin(rejoin.get());
    }

    private void failRestore(String translationKey) {
        RestoreSession.Phase phase = restoreSession.phase();
        if (phase == RestoreSession.Phase.IDLE) {
            MineBackup.LOGGER.debug("Ignoring restore failure event without an active session.");
            return;
        }
        restoreSession.reset();
        ClientHooks.restoreFailed(Component.translatable(translationKey));
    }

    private void failRestore(Map<String, String> fields, String translationKey) {
        if (!restoreSession.matchesActiveWorld(fields.get("world"))) {
            MineBackup.LOGGER.warn("Rejected restore failure event for an inactive world.");
            return;
        }
        failRestore(translationKey);
    }

    private void broadcastInformationalEvent(Map<String, String> fields, String event) {
        MinecraftServer currentServer = server;
        if (currentServer != null) {
            currentServer.executeIfPossible(() ->
                    broadcastInformationalEventOnServer(currentServer, fields, event));
        }
    }

    private void broadcastInformationalEventOnServer(
            MinecraftServer currentServer,
            Map<String, String> fields,
            String event) {
        Component world = literalOrUnknown(
                firstNonBlank(fields.get("world"), fields.get("folder")),
                "minebackup.message.unknown_world");
        Component file = literalOrUnknown(fields.get("file"), "minebackup.message.unknown_file");
        Component error = literalOrUnknown(fields.get("error"), "minebackup.message.unknown_error");
        Component message = switch (event) {
            case "backup_started" -> Component.translatable("minebackup.broadcast.backup.started", world);
            case "backup_success" -> Component.translatable("minebackup.broadcast.backup.success", world, file);
            case "backup_failed" -> Component.translatable("minebackup.broadcast.backup.failed", world, error);
            case "restore_started" -> Component.translatable("minebackup.broadcast.restore.started", world);
            case "restore_success" -> Component.translatable("minebackup.broadcast.restore.success", world);
            case "restore_failed" -> Component.translatable("minebackup.broadcast.restore.failed", world, error);
            case "auto_backup_started" -> Component.translatable("minebackup.broadcast.auto_backup.started", world);
            default -> null;
        };
        if (message != null) {
            currentServer.getPlayerList().broadcastSystemMessage(message, false);
        }
    }

    private void showHandshakeSuccessOnce(MinecraftServer currentServer, String mainVersion) {
        String displayVersion = mainVersion == null ? "?" : mainVersion;
        synchronized (this) {
            if (displayVersion.equals(lastHandshakeNoticeVersion)) {
                return;
            }
            lastHandshakeNoticeVersion = displayVersion;
        }
        currentServer.executeIfPossible(() -> currentServer.getPlayerList().broadcastSystemMessage(
                Component.translatable("minebackup.message.handshake.success", displayVersion),
                false));
    }

    private static LanState captureLanState(MinecraftServer server) {
        Config.HostReopen config = Config.get().hostReopen();
        if (!config.enabled() || !server.isPublished() || server.getPort() <= 0) {
            return new LanState(false, -1);
        }
        return new LanState(true, server.getPort());
    }

    private static void disconnectPlayers(MinecraftServer server, Component message) {
        ServerPlayer[] players = server.getPlayerList().getPlayers().toArray(ServerPlayer[]::new);
        for (ServerPlayer player : players) {
            if (!isSingleplayerHost(server, player)) {
                disconnectPlayer(player, message);
            }
        }
        for (ServerPlayer player : players) {
            if (isSingleplayerHost(server, player)) {
                disconnectPlayer(player, message);
            }
        }
    }

    private static void disconnectPlayer(ServerPlayer player, Component message) {
        try {
            player.connection.disconnect(message);
        } catch (RuntimeException exception) {
            MineBackup.LOGGER.warn(
                    "Failed to disconnect player '{}' for restore",
                    player.getGameProfile().name(),
                    exception);
        }
    }

    private static boolean isSingleplayerHost(MinecraftServer server, ServerPlayer player) {
        GameProfile owner = server.getSingleplayerProfile();
        return owner != null && owner.id().equals(player.getGameProfile().id());
    }

    private static String resolveLevelId(MinecraftServer server) {
        Path root = server.getWorldPath(LevelResource.ROOT);
        Path cursor = root;
        for (int depth = 0; depth < 6 && cursor != null; depth++) {
            if (Files.exists(cursor.resolve("level.dat")) && cursor.getFileName() != null) {
                String candidate = sanitizeLevelId(cursor.getFileName().toString());
                if (candidate != null) {
                    return candidate;
                }
            }
            cursor = cursor.getParent();
        }

        String levelName = sanitizeLevelId(server.getWorldData().getLevelName());
        if (levelName != null) {
            return levelName;
        }
        throw new IllegalStateException("Unable to resolve a safe Minecraft level id");
    }

    private static String sanitizeLevelId(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()
                || ".".equals(normalized)
                || "..".equals(normalized)
                || normalized.contains("/")
                || normalized.contains("\\")) {
            return null;
        }
        return normalized;
    }

    private static boolean readyForRestoreAck(Path root) {
        return canAcquireSessionLock(root) && canAccessCriticalFiles(root);
    }

    private static boolean canAcquireSessionLock(Path root) {
        Path lockPath = root.resolve("session.lock");
        if (!Files.exists(lockPath)) {
            return true;
        }
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            return lock != null;
        } catch (OverlappingFileLockException exception) {
            return false;
        } catch (IOException exception) {
            return false;
        }
    }

    private static boolean canAccessCriticalFiles(Path root) {
        if (!canOpenForWrite(root.resolve("level.dat"))
                || !canOpenForWrite(root.resolve("level.dat_old"))) {
            return false;
        }
        Path regionDirectory = root.resolve("region");
        if (!Files.isDirectory(regionDirectory)) {
            return true;
        }
        try (java.util.stream.Stream<Path> files = Files.list(regionDirectory)) {
            Path sample = files.filter(Files::isRegularFile).findFirst().orElse(null);
            return sample == null || canOpenForWrite(sample);
        } catch (IOException exception) {
            return false;
        }
    }

    private static boolean canOpenForWrite(Path path) {
        if (!Files.isRegularFile(path)) {
            return true;
        }
        try (FileChannel ignored = FileChannel.open(path, StandardOpenOption.WRITE)) {
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    private static Component literalOrUnknown(String value, String unknownTranslationKey) {
        return value == null || value.isBlank()
                ? Component.translatable(unknownTranslationKey)
                : Component.literal(value);
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    @Override
    public void close() {
        ClientHooks.clear();
        restoreSession.reset();
        MinecraftServer currentServer = server;
        if (currentServer != null) {
            currentServer.executeIfPossible(autoSave::unfreeze);
        }
        knotLink.close();
        coordinator.shutdownNow();
    }

    private static ThreadFactory daemonThreadFactory() {
        return task -> {
            Thread thread = new Thread(task, "minebackup-restore-coordinator");
            thread.setDaemon(true);
            return thread;
        };
    }

    private record LanState(boolean reopen, int port) {
    }

    private final class ReleasePoller implements Runnable {
        private final MinecraftServer restoreServer;
        private final Path worldRoot;
        private final long deadlineNanos = System.nanoTime() + RELEASE_TIMEOUT_NANOS;
        private final AtomicInteger readyStreak = new AtomicInteger();
        private volatile ScheduledFuture<?> future;
        private volatile boolean serverStopped;
        private volatile boolean playersCleared;

        private ReleasePoller(MinecraftServer restoreServer) {
            this.restoreServer = restoreServer;
            worldRoot = restoreServer.getWorldPath(LevelResource.ROOT);
        }

        private void markServerStopped(boolean allPlayersCleared) {
            serverStopped = true;
            playersCleared = allPlayersCleared;
            if (!allPlayersCleared) {
                MineBackup.LOGGER.error(
                        "Integrated server stopped before all players were disconnected.");
            }
        }

        @Override
        public void run() {
            if (!restoreSession.isReleasingServer()) {
                cancel();
                return;
            }
            if (System.nanoTime() >= deadlineNanos) {
                MineBackup.LOGGER.error("Timed out waiting for integrated server files to be released.");
                restoreSession.reset();
                ClientHooks.restoreFailed(Component.translatable("minebackup.message.restore.release_timeout"));
                cancel();
                return;
            }
            if (!serverStopped || !playersCleared || !readyForRestoreAck(worldRoot)) {
                readyStreak.set(0);
                return;
            }
            if (readyStreak.incrementAndGet() < READY_STREAK_REQUIRED) {
                return;
            }
            if (!restoreSession.markServerReleased()) {
                cancel();
                return;
            }

            cancel();
            knotLink.query(KnotLinkRequest.command("WORLD_SAVE_AND_EXIT_COMPLETE").conversation())
                    .whenComplete((response, error) -> {
                        if (error == null && response.isOk()) {
                            return;
                        }
                        if (error != null) {
                            MineBackup.LOGGER.error(
                                    "Failed to acknowledge world release to the backend",
                                    error);
                        } else {
                            MineBackup.LOGGER.error(
                                    "Backend rejected world release acknowledgement: {}",
                                    response.displayMessage());
                        }
                        restoreSession.reset();
                        ClientHooks.restoreFailed(Component.translatable(
                                "minebackup.message.restore.ack_failed"));
                    });
        }

        private void cancel() {
            ScheduledFuture<?> current = future;
            if (current != null) {
                current.cancel(false);
            }
            if (releasePoller == this) {
                releasePoller = null;
            }
        }
    }
}
