package com.leafuke.minebackup.runtime;

import com.leafuke.minebackup.MineBackup;
import com.leafuke.minebackup.ModInfo;
import com.leafuke.minebackup.api.v2.AutoBackupState;
import com.leafuke.minebackup.api.v2.BackupCatalogRequest;
import com.leafuke.minebackup.api.v2.BackupCatalogResult;
import com.leafuke.minebackup.api.v2.BackupRequest;
import com.leafuke.minebackup.api.v2.BackupResult;
import com.leafuke.minebackup.api.v2.MineBackupApi;
import com.leafuke.minebackup.api.v2.MessageSlot;
import com.leafuke.minebackup.api.v2.OperationFailure;
import com.leafuke.minebackup.api.v2.OperationHandle;
import com.leafuke.minebackup.api.v2.OperationPhase;
import com.leafuke.minebackup.api.v2.RestoreRequest;
import com.leafuke.minebackup.api.v2.RestoreResult;
import com.leafuke.minebackup.api.v2.RuntimeEnvironment;
import com.leafuke.minebackup.api.v2.RuntimeStatus;
import com.leafuke.minebackup.client.ClientHooks;
import com.leafuke.minebackup.client.RestoreUiMessages;
import com.leafuke.minebackup.config.Config;
import com.leafuke.minebackup.dedicated.DedicatedRestoreManager;
import com.leafuke.minebackup.knotlink.KnotLinkClient;
import com.leafuke.minebackup.knotlink.protocol.KnotLinkRequest;
import com.leafuke.minebackup.restore.RestoreSession;
import com.leafuke.minebackup.update.VersionNumber;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.TickEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.UUID;

public final class MineBackupRuntime implements MineBackupApi, AutoCloseable {
    private static final String MINIMUM_MAIN_VERSION = "1.16.0";
    private static final long RELEASE_TIMEOUT_NANOS = Duration.ofSeconds(8).toNanos();
    private static final int READY_STREAK_REQUIRED = 3;

    private final KnotLinkClient knotLink = new KnotLinkClient();
    private final RestoreSession restoreSession = new RestoreSession();
    private final ScheduledExecutorService coordinator = Executors.newSingleThreadScheduledExecutor(
            daemonThreadFactory());
    private final CurrentWorldOperationCoordinator operations = new CurrentWorldOperationCoordinator(
            knotLink::query,
            coordinator,
            () -> this.operationsAvailable,
            () -> this.dedicatedServer,
            () -> Config.get().restore().countdownSeconds(),
            countdownListener());
    private final AutoSaveController autoSave =
            new AutoSaveController(operations::failActiveBackupTimeout);
    private final AutoBackupScheduler automaticBackups =
            new AutoBackupScheduler(operations, coordinator);
    private final DedicatedRestoreManager dedicatedRestore =
            new DedicatedRestoreManager(Config.restartDirectory());

    private volatile MinecraftServer server;
    private final FeedbackRouter feedback = new FeedbackRouter(() -> server);
    private volatile boolean operationsAvailable;
    private volatile boolean dedicatedServer;
    private volatile String lastHandshakeNoticeVersion;
    private volatile ReleasePoller releasePoller;

    public void registerEvents() {
        MinecraftForge.EVENT_BUS.addListener(this::onServerStarting);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStopping);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStopped);
        MinecraftForge.EVENT_BUS.addListener(this::onServerTick);
    }

    public KnotLinkClient knotLink() {
        return knotLink;
    }

    public void completeClientRestore(boolean success, String reason) {
        operations.completeClientRejoin(success, reason);
        restoreSession.reset();
    }

    private void onServerStarting(ServerStartingEvent event) {
        MinecraftServer startingServer = event.getServer();
        server = startingServer;
        operationsAvailable = true;
        dedicatedServer = startingServer.isDedicatedServer();
        lastHandshakeNoticeVersion = null;
        Config.load();
        dedicatedRestore.loadLastResult();
        knotLink.startSubscriber(this::handleSignal);
        automaticBackups.serverStarted();

        if (dedicatedServer) {
            DedicatedRestoreManager.Availability availability = dedicatedRestore.availability(
                    Config.get().dedicatedRestore(),
                    Path.of("").toAbsolutePath());
            if (availability.available()) {
                MineBackup.LOGGER.info("MineBackup dedicated restore sidecar is available.");
            } else {
                MineBackup.LOGGER.warn(
                        "MineBackup dedicated restore is unavailable: {}",
                        availability.reason());
            }
        }

        if (Config.consumeLegacyAutoBackupMigrationNotice()) {
            MineBackup.LOGGER.warn(
                    "Disabled legacy target-based automatic backup. Run /mb auto start <minutes> to configure current-world hot backup.");
            startingServer.executeIfPossible(() ->
                    feedback.broadcastOnServer(
                            startingServer,
                            Component.translatable("minebackup.message.auto.legacy_disabled")));
        }
    }

    private void onServerStopping(ServerStoppingEvent event) {
        MinecraftServer stoppingServer = event.getServer();
        operationsAvailable = false;
        automaticBackups.serverStopped();
        operations.serverStopping(
                restoreSession.phase() != RestoreSession.Phase.IDLE);
        if (autoSave.unfreeze()) {
            MineBackup.LOGGER.warn("Server stopped while auto-save was frozen; save state was restored.");
        }
        if (dedicatedServer) {
            close();
        }
    }

    private void onServerStopped(ServerStoppedEvent event) {
        MinecraftServer stoppedServer = event.getServer();
        ReleasePoller currentPoller = releasePoller;
        if (currentPoller != null && currentPoller.restoreServer == stoppedServer) {
            currentPoller.markServerStopped(
                    stoppedServer.getPlayerList().getPlayers().isEmpty());
        }
        if (server == stoppedServer) {
            server = null;
            operationsAvailable = false;
        }
        if (restoreSession.phase() == RestoreSession.Phase.IDLE) {
            restoreSession.reset();
        }
    }

    private void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            autoSave.tick(event.getServer());
        }
    }

    private void handleSignal(Map<String, String> fields) {
        String event = fields.get("event");
        if (event == null) {
            return;
        }

        if (isBackupTerminal(fields, event)) {
            handleBackupTerminal(fields, event);
            return;
        }
        operations.handleSignal(fields);

        switch (event) {
            case "handshake" -> handleHandshake(fields);
            case "pre_hot_backup" -> handlePreHotBackup(fields);
            case "hot_restore_requested" -> handleHotRestoreRequested(fields);
            case "pre_hot_restore" -> handlePreHotRestore(fields);
            case "restore_finished" -> handleRestoreFinished(fields);
            case "restore_cancelled" -> failRestore(
                    fields,
                    "minebackup.message.restore.cancelled");
            case "rejoin_world" -> handleRejoinWorld(fields);
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
        if (!VersionNumber.isAtLeast(mainVersion, MINIMUM_MAIN_VERSION)) {
            currentServer.executeIfPossible(() -> feedback.broadcastOnServer(
                    currentServer,
                    Component.translatable(
                            "minebackup.message.handshake.main_version_incompatible",
                            mainVersion == null ? "?" : mainVersion,
                            MINIMUM_MAIN_VERSION)));
            return;
        }
        if (!VersionNumber.isAtLeast(ModInfo.version(), minimumModVersion)) {
            currentServer.executeIfPossible(() -> feedback.broadcastOnServer(
                    currentServer,
                    Component.translatable(
                            "minebackup.message.handshake.version_incompatible",
                            ModInfo.version(),
                            minimumModVersion == null ? "?" : minimumModVersion)));
            return;
        }
        if (autoSave.isFrozen()) {
            MineBackup.LOGGER.warn("Rejected KnotLink handshake while a hot backup is active.");
            return;
        }

        if (parsedAction.get() == RestoreSession.Action.RESTORE && dedicatedServer) {
            DedicatedRestoreManager.Availability availability = dedicatedRestore.availability(
                    Config.get().dedicatedRestore(),
                    Path.of("").toAbsolutePath());
            if (!availability.available()) {
                MineBackup.LOGGER.warn(
                        "Rejected dedicated restore handshake: {}",
                        availability.reason());
                return;
            }
        }

        if (parsedAction.get() == RestoreSession.Action.RESTORE
                && operations.activeRestore().isEmpty()) {
            UUID remoteRequestId;
            try {
                remoteRequestId = UUID.fromString(fields.getOrDefault("request_id", ""));
            } catch (IllegalArgumentException exception) {
                remoteRequestId = UUID.randomUUID();
            }
            if (!operations.adoptRemoteRestore(remoteRequestId)) {
                MineBackup.LOGGER.warn("Rejected remote FolderRewind restore while another operation is active.");
                return;
            }
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
                var presentation = operations.activePresentation();
                operations.failActiveBackup(
                        OperationFailure.Code.SAVE_TIMEOUT,
                        "Minecraft could not save the current world");
                feedback.optional(
                        presentation,
                        MessageSlot.BACKUP_FAILED,
                        Component.translatable("minebackup.broadcast.hot_backup.save_failed"),
                        "current_save",
                        "save failed");
                return;
            }
            if (!autoSave.freeze(currentServer)) {
                operations.failActiveBackup(
                        OperationFailure.Code.BUSY,
                        "Minecraft automatic saving is already frozen");
                return;
            }

            knotLink.query(KnotLinkRequest.command("WORLD_SAVED").conversation())
                    .whenComplete((response, error) -> {
                        if (error == null && response.isOk()) {
                            currentServer.executeIfPossible(() -> feedback.optional(
                                    operations.activePresentation(),
                                    MessageSlot.BACKUP_STARTED,
                                    Component.translatable("minebackup.broadcast.hot_backup.complete"),
                                    "current_save"));
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
                            var presentation = operations.activePresentation();
                            autoSave.unfreeze();
                            operations.failActiveBackup(
                                    OperationFailure.Code.BACKEND_REJECTED,
                                    error == null
                                            ? response.displayMessage()
                                            : error.getMessage());
                            feedback.optional(
                                    presentation,
                                    MessageSlot.BACKUP_FAILED,
                                    Component.translatable("minebackup.broadcast.hot_backup.ack_failed"),
                                    "current_save",
                                    error == null ? response.displayMessage() : error.getMessage());
                        });
                    });
        });
    }

    private void handleBackupTerminal(Map<String, String> fields, String event) {
        MinecraftServer currentServer = server;
        if (currentServer != null) {
            currentServer.executeIfPossible(() -> {
                if (autoSave.unfreeze()) {
                    feedback.optional(
                            operations.activePresentation(),
                            "backup_failed".equals(event)
                                    ? MessageSlot.BACKUP_FAILED
                                    : MessageSlot.BACKUP_SUCCEEDED,
                            Component.translatable("minebackup.broadcast.autosave.resumed"),
                            "current_save");
                }
                broadcastInformationalEventOnServer(currentServer, fields, event);
                operations.handleSignal(fields);
            });
        } else {
            autoSave.unfreeze();
            operations.handleSignal(fields);
        }
    }

    private static boolean isBackupTerminal(Map<String, String> fields, String event) {
        if ("backup_success".equals(event) || "backup_failed".equals(event)) {
            return true;
        }
        if (!"command_completed".equals(event) && !"command_failed".equals(event)) {
            return false;
        }
        String command = fields.get("command");
        return command == null || "BACKUP".equalsIgnoreCase(command);
    }

    private void handleHotRestoreRequested(Map<String, String> fields) {
        RemoteRestoreRequest remoteRequest;
        try {
            remoteRequest = RemoteRestoreRequest.parse(fields);
        } catch (IllegalArgumentException exception) {
            rejectRemoteRestoreRequest(exception.getMessage());
            return;
        }

        OperationHandle<RestoreResult> handle =
                restoreCurrent(remoteRequest.request(), remoteRequest.requestId());
        if (handle.phase() != OperationPhase.REJECTED) {
            String backup = remoteRequest.request().backupId()
                    .map(id -> id.value())
                    .orElse("<latest>");
            MineBackup.LOGGER.info(
                    "Accepted FolderRewind UI hot restore request {} for {}.",
                    remoteRequest.requestId(),
                    backup);
            return;
        }

        handle.completion().thenAccept(result -> rejectRemoteRestoreRequest(
                result.failure()
                        .map(OperationFailure::message)
                        .filter(message -> !message.isBlank())
                        .orElse("Hot restore request was rejected")));
    }

    private void rejectRemoteRestoreRequest(String reason) {
        String safeReason = reason == null || reason.isBlank()
                ? "Invalid hot restore request"
                : reason;
        MineBackup.LOGGER.warn("Rejected FolderRewind UI hot restore request: {}", safeReason);
        feedback.broadcast(Component.translatable(
                "minebackup.message.restore.remote_request_rejected",
                safeReason));
    }

    private void handlePreHotRestore(Map<String, String> fields) {
        if (!restoreSession.consumeHandshake(RestoreSession.Action.RESTORE, fields.get("world"))) {
            MineBackup.LOGGER.warn("Rejected pre_hot_restore without a matching, fresh handshake.");
            return;
        }

        MinecraftServer currentServer = server;
        if (currentServer == null) {
            operations.failActiveRestore(
                    OperationFailure.Code.NO_ACTIVE_SERVER,
                    "Hot restore cannot start without a server");
            return;
        }
        currentServer.executeIfPossible(() -> {
            if (dedicatedServer) {
                beginDedicatedRestore(currentServer);
            } else {
                beginIntegratedRestore(currentServer);
            }
        });
    }

    private void beginDedicatedRestore(MinecraftServer currentServer) {
        Config.DedicatedRestore config = Config.get().dedicatedRestore();
        DedicatedRestoreManager.Availability availability = dedicatedRestore.availability(
                config, Path.of("").toAbsolutePath());
        if (!availability.available()) {
            operations.failActiveRestore(
                    OperationFailure.Code.RESTART_UNAVAILABLE,
                    availability.reason());
            return;
        }
        if (!LocalSaveCoordinator.save(currentServer)) {
            operations.failActiveRestore(
                    OperationFailure.Code.SAVE_TIMEOUT,
                    "Minecraft could not save all worlds before dedicated restore");
            return;
        }
        InternalRestoreHandle handle = operations.activeRestore().orElse(null);
        if (handle == null) {
            MineBackup.LOGGER.warn("Dedicated restore has no matching current-world operation.");
            return;
        }
        Path worldPath = currentServer.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        String worldId = restoreSession.activeWorld().orElse(null);
        if (worldId == null) {
            operations.failActiveRestore(
                    OperationFailure.Code.PROTOCOL_ERROR,
                    "Dedicated restore handshake did not identify a world");
            return;
        }
        DedicatedRestoreManager.Handoff handoff = dedicatedRestore.prepare(
                config,
                Path.of("").toAbsolutePath(),
                worldPath,
                worldId,
                handle.id(),
                handle.callerId());
        if (!handoff.accepted()) {
            var presentation = operations.activePresentation();
            MineBackup.LOGGER.error(
                    "Dedicated restore sidecar handoff failed; server remains online: {}",
                    handoff.reason());
            operations.failActiveRestore(
                    OperationFailure.Code.SIDECAR_START_FAILED,
                    handoff.reason());
            restoreSession.reset();
            feedback.optional(
                    presentation,
                    MessageSlot.RESTORE_FAILED,
                    Component.translatable("minebackup.message.restore.failed"),
                    worldId,
                    "",
                    handoff.reason());
            return;
        }

        feedback.optional(
                operations.activePresentation(),
                MessageSlot.RESTORE_PREPARING,
                Component.translatable("minebackup.message.restore.preparing"),
                worldId,
                "");
        Component kick = feedback.resolve(
                operations.activePresentation(),
                MessageSlot.RESTORE_KICK,
                Component.translatable("minebackup.message.restore.kick"),
                worldId,
                "");
        feedback.optional(
                operations.activePresentation(),
                MessageSlot.RESTORE_REJOIN,
                Component.translatable("minebackup.message.restore.dedicated_handoff"),
                worldId,
                "");
        disconnectPlayers(currentServer, kick);
        operations.completeDedicatedHandoff();
        currentServer.halt(false);
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
            var presentation = operations.activePresentation();
            operations.failActiveRestore(
                    OperationFailure.Code.RESTORE_FAILED,
                    exception.getMessage());
            feedback.optional(
                    presentation,
                    MessageSlot.RESTORE_FAILED,
                    Component.translatable("minebackup.message.restore.failed"),
                    "",
                    "",
                    exception.getMessage());
            return;
        }
        if (!LocalSaveCoordinator.save(currentServer)) {
            var presentation = operations.activePresentation();
            operations.failActiveRestore(
                    OperationFailure.Code.RESTORE_FAILED,
                    "Minecraft could not save the current world before restore");
            feedback.optional(
                    presentation,
                    MessageSlot.RESTORE_FAILED,
                    Component.translatable("minebackup.message.restore.failed"),
                    "",
                    "",
                    "save failed");
            return;
        }

        LanState lanState = captureLanState(currentServer);
        if (!restoreSession.beginRestore(levelId, lanState.reopen(), lanState.port())) {
            operations.failActiveRestore(
                    OperationFailure.Code.BUSY,
                    "Restore session is not ready");
            return;
        }

        feedback.optional(
                operations.activePresentation(),
                MessageSlot.RESTORE_PREPARING,
                Component.translatable("minebackup.message.restore.preparing"));
        disconnectPlayers(
                currentServer,
                feedback.resolve(
                        operations.activePresentation(),
                        MessageSlot.RESTORE_KICK,
                        Component.translatable("minebackup.message.restore.kick")));
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
        var presentation = operations.activePresentation();
        String world = fields.getOrDefault("world", "");
        String backup = fields.getOrDefault("file", "");
        ClientHooks.requestRejoin(
                rejoin.get(),
                new RestoreUiMessages(
                        feedback.resolve(
                                presentation,
                                MessageSlot.RESTORE_REJOIN,
                                Component.translatable("minebackup.message.restore.rejoining"),
                                world,
                                backup),
                        feedback.resolve(
                                presentation,
                                MessageSlot.RESTORE_SUCCEEDED,
                                Component.translatable("minebackup.message.restore.success_overlay"),
                                world,
                                backup)));
    }

    private void failRestore(String translationKey) {
        RestoreSession.Phase phase = restoreSession.phase();
        if (phase == RestoreSession.Phase.IDLE) {
            MineBackup.LOGGER.debug("Ignoring restore failure event without an active session.");
            return;
        }
        var presentation = operations.activePresentation();
        restoreSession.reset();
        operations.failActiveRestore(
                OperationFailure.Code.RESTORE_FAILED,
                translationKey);
        ClientHooks.restoreFailed(feedback.resolve(
                presentation,
                MessageSlot.RESTORE_FAILED,
                Component.translatable(translationKey),
                "",
                "",
                translationKey));
    }

    private void failRestore(Map<String, String> fields, String translationKey) {
        if (!restoreSession.matchesActiveWorld(fields.get("world"))) {
            if (operations.activeRestore().isEmpty()) {
                MineBackup.LOGGER.debug(
                        "Ignored late restore failure after the local restore operation had already ended.");
            } else {
                MineBackup.LOGGER.warn("Rejected restore failure event for an inactive world.");
            }
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
            default -> null;
        };
        if (message != null) {
            MessageSlot slot = switch (event) {
                case "backup_started" -> MessageSlot.BACKUP_STARTED;
                case "backup_success" -> MessageSlot.BACKUP_SUCCEEDED;
                case "backup_failed" -> MessageSlot.BACKUP_FAILED;
                case "restore_started" -> MessageSlot.RESTORE_PREPARING;
                case "restore_success" -> MessageSlot.RESTORE_SUCCEEDED;
                case "restore_failed" -> MessageSlot.RESTORE_FAILED;
                default -> throw new IllegalStateException("Unexpected feedback event: " + event);
            };
            if (operations.activePresentation().feedbackPolicy()
                    != com.leafuke.minebackup.api.v2.FeedbackPolicy.CALLER_MANAGED) {
                feedback.broadcastOnServer(
                        currentServer,
                        feedback.resolve(
                                operations.activePresentation(),
                                slot,
                                message,
                                world,
                                file,
                                error));
            }
        }
    }

    private void showHandshakeSuccessOnce(MinecraftServer currentServer, String mainVersion) {
        if (operations.activePresentation().feedbackPolicy()
                == com.leafuke.minebackup.api.v2.FeedbackPolicy.CALLER_MANAGED) {
            return;
        }
        String displayVersion = mainVersion == null ? "?" : mainVersion;
        synchronized (this) {
            if (displayVersion.equals(lastHandshakeNoticeVersion)) {
                return;
            }
            lastHandshakeNoticeVersion = displayVersion;
        }
        currentServer.executeIfPossible(() -> feedback.broadcastOnServer(
                currentServer,
                Component.translatable("minebackup.message.handshake.success", displayVersion)));
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
                    player.getGameProfile().getName(),
                    exception);
        }
    }

    private static boolean isSingleplayerHost(MinecraftServer server, ServerPlayer player) {
        GameProfile owner = server.getSingleplayerProfile();
        return owner != null && owner.getId().equals(player.getGameProfile().getId());
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
        operationsAvailable = false;
        restoreSession.reset();
        automaticBackups.close();
        operations.close();
        // close() can run from SERVER_STOPPING, after Minecraft has rejected
        // all new server-thread tasks. Restoring the noSave flags is a local,
        // synchronous cleanup and must never enqueue work during shutdown.
        autoSave.unfreeze();
        knotLink.close();
        coordinator.shutdownNow();
    }

    @Override
    public int apiVersion() {
        return MineBackupApi.API_VERSION;
    }

    @Override
    public OperationHandle<BackupResult> backupCurrent(BackupRequest request) {
        return operations.backupCurrent(request);
    }

    @Override
    public OperationHandle<RestoreResult> restoreCurrent(RestoreRequest request) {
        return restoreCurrent(request, UUID.randomUUID());
    }

    private OperationHandle<RestoreResult> restoreCurrent(
            RestoreRequest request,
            UUID requestId) {
        if (dedicatedServer && operationsAvailable) {
            DedicatedRestoreManager.Availability availability = dedicatedRestore.availability(
                    Config.get().dedicatedRestore(),
                    Path.of("").toAbsolutePath());
            if (!availability.available()) {
                return operations.rejectRestoreRequest(
                        request,
                        requestId,
                        OperationFailure.Code.RESTART_UNAVAILABLE,
                        availability.reason());
            }
        }
        return operations.restoreCurrent(request, requestId);
    }

    public Optional<InternalRestoreHandle> pendingRestore() {
        return operations.pendingRestore();
    }

    public RestoreControlResult confirmPendingRestore() {
        return operations.pendingRestore()
                .map(InternalRestoreHandle::confirm)
                .orElse(RestoreControlResult.NOT_PENDING);
    }

    public RestoreControlResult cancelPendingRestore() {
        return operations.pendingRestore()
                .map(InternalRestoreHandle::cancel)
                .orElse(RestoreControlResult.NOT_PENDING);
    }

    public AutoBackupUpdateResult startAutomaticBackup(Duration interval) {
        return automaticBackups.start(interval);
    }

    public AutoBackupUpdateResult stopAutomaticBackup() {
        return automaticBackups.stop();
    }

    public AutoBackupState automaticBackupState() {
        return automaticBackups.state();
    }

    @Override
    public CompletionStage<BackupCatalogResult> listCurrentBackups(BackupCatalogRequest request) {
        return operations.listCurrentBackups(request);
    }

    @Override
    public RuntimeStatus runtimeStatus() {
        RuntimeEnvironment environment = server == null
                ? RuntimeEnvironment.NONE
                : dedicatedServer ? RuntimeEnvironment.DEDICATED : RuntimeEnvironment.INTEGRATED;
        DedicatedRestoreManager.Availability availability = dedicatedServer
                ? dedicatedRestore.availability(
                        Config.get().dedicatedRestore(),
                        Path.of("").toAbsolutePath())
                : new DedicatedRestoreManager.Availability(
                        false,
                        "Not running on a dedicated server",
                        new com.leafuke.minebackup.dedicated.RestartScriptResolver.Resolution(
                                false, null, java.util.List.of(), "Not dedicated"));
        return new RuntimeStatus(
                environment,
                operationsAvailable,
                dedicatedServer && availability.available(),
                dedicatedServer && availability.available()
                        ? Optional.empty()
                        : Optional.of(availability.reason()),
                operations.activeSnapshot(),
                automaticBackups.state(),
                dedicatedRestore.lastStatus());
    }

    private CurrentWorldOperationCoordinator.CountdownListener countdownListener() {
        return new CurrentWorldOperationCoordinator.CountdownListener() {
            @Override
            public void onStarted(InternalRestoreHandle handle, int seconds) {
                feedback.optional(
                        operations.activePresentation(),
                        MessageSlot.RESTORE_COUNTDOWN_STARTED,
                        Component.translatable("minebackup.message.restore.countdown.started", seconds),
                        seconds);
            }

            @Override
            public void onTick(InternalRestoreHandle handle, int seconds) {
                MutableComponent message = Component.translatable(
                        "minebackup.message.restore.countdown.tick",
                        seconds);
                message.append(Component.literal(" "));
                message.append(actionLink(
                        "minebackup.message.restore.countdown.confirm",
                        "/mb confirm"));
                message.append(Component.literal(" "));
                message.append(actionLink(
                        "minebackup.message.restore.countdown.cancel",
                        "/mb stop"));
                feedback.optional(
                        operations.activePresentation(),
                        MessageSlot.RESTORE_COUNTDOWN_TICK,
                        message,
                        seconds);
            }

            @Override
            public void onConfirmed(InternalRestoreHandle handle) {
                // onSubmitted emits the single player-facing transition message.
            }

            @Override
            public void onCancelled(InternalRestoreHandle handle) {
                feedback.optional(
                        operations.activePresentation(),
                        MessageSlot.RESTORE_CANCEL,
                        Component.translatable("minebackup.message.restore.countdown.cancelled"));
            }

            @Override
            public void onSubmitted(InternalRestoreHandle handle) {
                feedback.optional(
                        operations.activePresentation(),
                        MessageSlot.RESTORE_PREPARING,
                        Component.translatable("minebackup.message.restore.countdown.submitted"));
            }
        };
    }

    private static MutableComponent actionLink(String translationKey, String command) {
        return Component.translatable(translationKey).withStyle(style -> style
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.translatable(translationKey)))
                .withUnderlined(true));
    }

    private void broadcast(Component message) {
        feedback.broadcast(message);
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
                operations.failActiveRestore(
                        OperationFailure.Code.RESTORE_FAILED,
                        "Timed out waiting for integrated server files to be released");
                ClientHooks.restoreFailed(Component.translatable("minebackup.message.restore.release_timeout"));
                cancel();
                return;
            }
            if (!serverStopped || !playersCleared || !WorldReleaseProbe.isReleased(worldRoot)) {
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
                        operations.failActiveRestore(
                                OperationFailure.Code.BACKEND_REJECTED,
                                error == null
                                        ? response.displayMessage()
                                        : error.getMessage());
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
