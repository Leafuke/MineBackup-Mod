package com.leafuke.minebackup.runtime;

import com.leafuke.minebackup.MineBackup;
import com.leafuke.minebackup.command.FailureMessages;
import com.leafuke.minebackup.knotlink.KnotLinkCommunicationException;
import com.leafuke.minebackup.ModInfo;
import com.leafuke.minebackup.api.v2.AutoBackupState;
import com.leafuke.minebackup.api.v2.BackupCatalogRequest;
import com.leafuke.minebackup.api.v2.BackupCatalogResult;
import com.leafuke.minebackup.api.v2.BackupRequest;
import com.leafuke.minebackup.api.v2.BackupResult;
import com.leafuke.minebackup.api.v2.CurrentWorldAutomationMode;
import com.leafuke.minebackup.api.v2.CurrentWorldAutomationState;
import com.leafuke.minebackup.api.v2.MineBackupApi;
import com.leafuke.minebackup.api.v2.MessageSlot;
import com.leafuke.minebackup.api.v2.OperationFailure;
import com.leafuke.minebackup.api.v2.OperationHandle;
import com.leafuke.minebackup.api.v2.RestoreRequest;
import com.leafuke.minebackup.api.v2.RestoreResult;
import com.leafuke.minebackup.api.v2.RuntimeEnvironment;
import com.leafuke.minebackup.api.v2.RuntimeStatus;
import com.leafuke.minebackup.client.ClientHooks;
import com.leafuke.minebackup.client.RestoreUiMessages;
import com.leafuke.minebackup.command.CommandCapability;
import com.leafuke.minebackup.command.CommandPermissions;
import com.leafuke.minebackup.config.Config;
import com.leafuke.minebackup.dedicated.DedicatedRestoreManager;
import com.leafuke.minebackup.knotlink.KnotLinkClient;
import com.leafuke.minebackup.knotlink.protocol.KnotLinkRequest;
import com.leafuke.minebackup.restore.RestoreSession;
import com.leafuke.minebackup.update.VersionNumber;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

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
    private static final int READY_STREAK_REQUIRED = 3;

    private final KnotLinkClient knotLink = new KnotLinkClient();
    private final ScheduledExecutorService coordinator = Executors.newSingleThreadScheduledExecutor(
            daemonThreadFactory());
    private final CurrentWorldOperationCoordinator operations = new CurrentWorldOperationCoordinator(
            knotLink::query,
            coordinator,
            () -> this.operationsAvailable,
            () -> this.dedicatedServer,
            () -> Config.get().restore().countdownSeconds(),
            countdownListener());
    private final AutoSaveController autoSave = new AutoSaveController();
    private final java.util.concurrent.ConcurrentMap<UUID, Runnable> pendingCleanup = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ExecutorService sidecarWorker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "minebackup-sidecar-start");
        thread.setDaemon(true);
        return thread;
    });
    { operations.bindLifecycle(() -> knotLink.signalChannelState()
            == com.leafuke.minebackup.api.v2.BackendStatusResult.ChannelState.CONNECTED, this::finishWorldOperation); }
    private final AutoBackupScheduler automaticBackups =
            new AutoBackupScheduler(operations, coordinator, this::broadcastAutomationReminder);
    private final DedicatedRestoreManager dedicatedRestore =
            new DedicatedRestoreManager(Config.restartDirectory());

    private volatile MinecraftServer server;
    private final FeedbackRouter feedback = new FeedbackRouter(() -> server);
    private volatile boolean operationsAvailable;
    private volatile boolean dedicatedServer;
    private volatile String lastHandshakeNoticeVersion;
    private volatile ReleasePoller releasePoller;

    public void registerEvents() {
        ServerLifecycleEvents.SERVER_STARTING.register(this::onServerStarting);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
        ServerLifecycleEvents.SERVER_STOPPED.register(this::onServerStopped);
        ServerTickEvents.END_SERVER_TICK.register(ignored -> operations.checkSessionTimeout());
        ServerPlayConnectionEvents.JOIN.register(this::onPlayerJoin);
    }

    public KnotLinkClient knotLink() {
        return knotLink;
    }

    public void completeClientRestore(boolean success, String reason) {
        operations.completeClientRejoin(success, reason);
    }

    private void onPlayerJoin(
            net.minecraft.server.network.ServerGamePacketListenerImpl handler,
            net.fabricmc.fabric.api.networking.v1.PacketSender sender,
            MinecraftServer server) {
        ServerPlayer player = handler.player;
        // Delay 1 second to ensure player is fully loaded
        coordinator.schedule(
                () -> server.executeIfPossible(() -> sendAutomationStatusWelcome(server, player)),
                1L,
                TimeUnit.SECONDS);
    }

    private void sendAutomationStatusWelcome(MinecraftServer joiningServer, ServerPlayer player) {
        if (server != joiningServer || !operationsAvailable
                || joiningServer.getPlayerList().getPlayer(player.getUUID()) != player) {
            return;
        }
        AutomationWelcomeMessage.build(
                        currentWorldAutomation(),
                        Config.get().joinStatusNotificationMode(),
                        CommandPermissions.canUse(
                                player.createCommandSourceStack(), CommandCapability.AUTOMATION))
                .ifPresent(player::sendSystemMessage);
    }

    private void onServerStarting(MinecraftServer startingServer) {
        server = startingServer;
        operationsAvailable = true;
        dedicatedServer = startingServer.isDedicatedServer();
        lastHandshakeNoticeVersion = null;
        Config.load();
        operations.worldStarted(startingServer.getWorldPath(LevelResource.ROOT));
        sidecarWorker.execute(dedicatedRestore::loadLastResult);
        knotLink.startSubscriber(this::handleSignal);
        AutoBackupScheduler.StartupResult autoStartup = automaticBackups.serverStarted(startingServer);

        switch (autoStartup.migration()) {
            case MIGRATED -> {
                MineBackup.LOGGER.info(
                        "Bound the legacy automatic backup schedule to world '{}'.",
                        autoStartup.worldName());
                startingServer.executeIfPossible(() -> feedback.broadcastOnServer(
                        startingServer,
                        Component.translatable(
                                "minebackup.message.auto.world_migrated",
                                autoStartup.worldName())));
            }
            case EXISTING_WORLD_PLAN_PRESERVED -> MineBackup.LOGGER.info(
                    "Kept the existing world automation plan for '{}' while removing the legacy global schedule.",
                    autoStartup.worldName());
            case WORLD_WRITE_FAILED, GLOBAL_CLEAR_FAILED, WORLD_LOAD_FAILED -> {
                MineBackup.LOGGER.error(
                        "Unable to complete automatic backup migration for '{}': {}",
                        autoStartup.worldName(),
                        autoStartup.migration());
                startingServer.executeIfPossible(() -> feedback.broadcastOnServer(
                        startingServer,
                        Component.translatable("minebackup.message.auto.world_migration_failed")));
            }
            case NONE -> {
            }
        }

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

    private void onServerStopping(MinecraftServer stoppingServer) {
        operationsAvailable = false;
        automaticBackups.serverStopped();
        operations.serverStopping(operations.preserveRestoreOnStop());
        pendingCleanup.values().forEach(Runnable::run);
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
            operationsAvailable = false;
        }
    }

    private void handleSignal(Map<String, String> fields) {
        MinecraftServer current = server;
        long generation = operations.worldGeneration();
        if (current != null && operationsAvailable && !current.isSameThread()) {
            current.executeIfPossible(() -> {
                if (server == current && operations.worldGeneration() == generation) dispatchSignal(fields);
            });
        } else dispatchSignal(fields);
    }

    private void dispatchSignal(Map<String, String> fields) {
        String event = fields.get("event");
        if (event == null) return;
        switch (event) {
            case "handshake" -> handleHandshake(fields);
            case "pre_hot_backup" -> handlePreHotBackup(fields);
            case "pre_hot_restore" -> handlePreHotRestore(fields);
            case "restore_finished" -> operations.restoreFinished(fields);
            case "rejoin_world" -> handleRejoinWorld(fields);
            default -> {
                var presentation = operations.activePresentation();
                long generation = operations.worldGeneration();
                boolean accepted = operations.handleSignal(fields) || operations.acceptsInformation(fields);
                MinecraftServer current = server;
                if (accepted && current != null) current.executeIfPossible(() -> {
                    if (server == current && operations.worldGeneration() == generation)
                        broadcastInformationalEventOnServer(current, fields, event, presentation);
                });
            }
        }
    }

    private void finishWorldOperation(WorldOperationSession.Token token,
            com.leafuke.minebackup.api.v2.OperationPresentation presentation,
            OperationFailure failure, Runnable complete) {
        if (token == null) { complete.run(); return; }
        boolean restore = operations.activeSnapshot().map(value -> value.type()
                == com.leafuke.minebackup.api.v2.OperationType.RESTORE).orElse(false);
        Runnable finish = () -> {
            if (pendingCleanup.remove(token.operationId()) == null) return;
            try {
                boolean resumed = autoSave.unfreeze(token);
                ReleasePoller poller = releasePoller;
                if (poller != null && poller.token.operationId().equals(token.operationId())) poller.cancel();
                if (failure != null) {
                    cancelSidecar(token.operationId());
                    MineBackup.LOGGER.warn("World operation {} ended: {}: {}", token.operationId(), failure.code(), failure.message());
                    if (resumed && failure.message().contains("backend outcome is unconfirmed"))
                        feedback.optional(presentation, MessageSlot.BACKUP_FAILED,
                                Component.translatable("minebackup.broadcast.backup.unconfirmed"));
                    if (restore) ClientHooks.restoreFailed(
                            feedback.resolve(presentation, MessageSlot.RESTORE_FAILED,
                                    Component.translatable("minebackup.message.restore.failed"), "", "", FailureMessages.message(failure.code())),
                            () -> operations.worldGeneration() == token.serverGeneration()
                                    && (operations.sessionToken() == null
                                        || operations.sessionToken().operationId().equals(token.operationId())));
                }
            } finally {
                complete.run();
            }
        };
        pendingCleanup.put(token.operationId(), finish);
        MinecraftServer current = server;
        if (current == null || current.isSameThread() || !operationsAvailable) finish.run();
        else current.executeIfPossible(finish);
    }

    private void cancelSidecar(UUID operationId) {
        var abandoned = dedicatedRestore.cancelPending(operationId);
        if (abandoned == null) return;
        try { sidecarWorker.execute(() -> dedicatedRestore.discard(abandoned)); }
        catch (java.util.concurrent.RejectedExecutionException closed) {
            // Child is already killed. Startup will classify any retained transient state.
            MineBackup.LOGGER.debug("Sidecar cleanup deferred to next startup", closed);
        }
    }

    private static boolean uncertainTransport(Throwable error) {
        if (error == null) return false;
        var code = KnotLinkCommunicationException.failure(error).code();
        return code == OperationFailure.Code.CONNECTION_CLOSED || code == OperationFailure.Code.RESPONSE_TIMEOUT;
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

        WorldOperationSession.Token token = operations.handshake(fields);
        if (token == null) {
            MineBackup.LOGGER.debug("Rejected unrelated or out-of-phase handshake.");
            return;
        }

        KnotLinkRequest responseRequest = KnotLinkRequest.command("HANDSHAKE_RESPONSE")
                .conversation()
                .field("mod_version", ModInfo.version());
        knotLink.query(responseRequest).whenComplete((response, error) -> {
            if (uncertainTransport(error)) return;
            if (error != null || response == null || !response.isOk()) {
                operations.failSession(token, OperationFailure.Code.COMMUNICATION_ERROR,
                        "Handshake acknowledgement failed");
                if (error != null) {
                    MineBackup.LOGGER.warn("Failed to acknowledge KnotLink handshake", error);
                } else {
                    MineBackup.LOGGER.warn("KnotLink handshake was rejected: {}", (response == null ? "Missing backend response" : response.displayMessage()));
                }
                return;
            }
            if (operations.isCurrent(token)) showHandshakeSuccessOnce(currentServer, mainVersion);
        });
    }

    private void handlePreHotBackup(Map<String, String> fields) {
        var token = operations.consumeHandshake(RestoreSession.Action.BACKUP, fields);
        MinecraftServer current = server;
        if (token == null || current == null) return;
        current.executeIfPossible(() -> {
            if (server != current || !operations.isCurrent(token)) return;
            if (!LocalSaveCoordinator.save(current)) {
                operations.failSession(token, OperationFailure.Code.SAVE_TIMEOUT, "Minecraft could not save the current world");
                return;
            }
            if (!operations.isCurrent(token)) return;
            if (!autoSave.freeze(current, token)) {
                operations.failSession(token, OperationFailure.Code.BUSY, "Automatic saving is already frozen");
                return;
            }
            if (!operations.markFrozen(token)) { autoSave.unfreeze(token); return; }
            knotLink.query(KnotLinkRequest.command("WORLD_SAVED").conversation(token.operationId()))
                    .whenComplete((response, error) -> {
                        if (!operations.isCurrent(token) || uncertainTransport(error)) return;
                        if (error != null || response == null || !response.isOk()) {
                            operations.failSession(token, error == null ? OperationFailure.Code.BACKEND_REJECTED
                                    : KnotLinkCommunicationException.failure(error).code(),
                                    error == null ? "Saved-world acknowledgement rejected" : error.toString());
                        } else current.executeIfPossible(() -> {
                            if (server == current && operations.isCurrent(token)) feedback.optional(
                                    operations.activePresentation(), MessageSlot.BACKUP_STARTED,
                                    Component.translatable("minebackup.broadcast.hot_backup.complete"), "current_save");
                        });
                    });
        });
    }

    private void handlePreHotRestore(Map<String, String> fields) {
        var token = operations.consumeHandshake(RestoreSession.Action.RESTORE, fields);
        MinecraftServer current = server;
        if (token == null || current == null) return;
        current.executeIfPossible(() -> {
            if (server != current || !operations.isCurrent(token)) return;
            if (dedicatedServer) beginDedicatedRestore(current, token);
            else beginIntegratedRestore(current, token);
        });
    }

    private void beginDedicatedRestore(MinecraftServer currentServer, WorldOperationSession.Token token) {
        Config.DedicatedRestore config = Config.get().dedicatedRestore();
        if (!LocalSaveCoordinator.save(currentServer)) {
            operations.failSession(token, OperationFailure.Code.SAVE_TIMEOUT, "Minecraft could not save before dedicated restore");
            return;
        }
        if (!operations.isCurrent(token)) return;
        InternalRestoreHandle handle = operations.activeRestore().orElse(null);
        if (handle == null) return;
        Path worldPath = currentServer.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        String worldId = operations.coordinatedWorld();
        String wireId = operations.wireRequestId();
        String wireFrom = operations.wireFrom();
        if (!operations.prepareDedicated(token, config.sidecarStartTimeoutSeconds())) return;
        sidecarWorker.execute(() -> {
            var handoff = dedicatedRestore.prepare(config, Path.of("").toAbsolutePath(), worldPath,
                    worldId, handle.id(), handle.callerId(), wireId, wireFrom,
                    () -> operations.isCurrent(token) && server == currentServer && operationsAvailable);
            if (!operations.isCurrent(token) || server != currentServer || !operationsAvailable) {
                dedicatedRestore.discard(handoff);
                return;
            }
            if (!handoff.accepted()) {
                operations.failSession(token, OperationFailure.Code.SIDECAR_START_FAILED, handoff.reason());
                return;
            }
            currentServer.executeIfPossible(() -> {
                if (!operations.isCurrent(token) || server != currentServer || !operationsAvailable) {
                    cancelSidecar(token.operationId());
                    return;
                }
                if (!dedicatedRestore.claim(handoff)) {
                    operations.failSession(token, OperationFailure.Code.SIDECAR_START_FAILED,
                            "Sidecar exited before shutdown handoff");
                    return;
                }
                var presentation = operations.activePresentation();
                feedback.optional(presentation, MessageSlot.RESTORE_PREPARING,
                        Component.translatable("minebackup.message.restore.preparing"), worldId, "");
                disconnectPlayers(currentServer, feedback.resolve(presentation, MessageSlot.RESTORE_KICK,
                        Component.translatable("minebackup.message.restore.kick"), worldId, ""));
                feedback.optional(presentation, MessageSlot.RESTORE_REJOIN,
                        Component.translatable("minebackup.message.restore.dedicated_handoff"), worldId, "");
                operationsAvailable = false;
                operations.completeDedicatedHandoff();
                currentServer.halt(false);
            });
        });
    }

    private void beginIntegratedRestore(MinecraftServer currentServer, WorldOperationSession.Token token) {
        if (!operations.isCurrent(token)) {
            MineBackup.LOGGER.warn("Ignored duplicate hot restore request.");
            return;
        }

        String levelId;
        try {
            levelId = resolveLevelId(currentServer);
        } catch (IllegalStateException exception) {
            MineBackup.LOGGER.error("Unable to identify the integrated world for hot restore", exception);
            var presentation = operations.activePresentation();
            operations.failSession(token,
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
            operations.failSession(token,
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

        if (!operations.isCurrent(token)) return;
        LanState lanState = captureLanState(currentServer);
        if (!operations.beginWorldRelease(token, new RestoreSession.RejoinInfo(levelId, lanState.reopen(), lanState.port()))) {
            operations.failSession(token,
                    OperationFailure.Code.BUSY,
                    "Restore session is not ready");
            return;
        }

        feedback.optional(
                operations.activePresentation(),
                MessageSlot.RESTORE_PREPARING,
                Component.translatable("minebackup.message.restore.preparing"));
        startReleaseWatcher(currentServer, token);
        disconnectPlayers(
                currentServer,
                feedback.resolve(
                        operations.activePresentation(),
                        MessageSlot.RESTORE_KICK,
                        Component.translatable("minebackup.message.restore.kick")));
    }

    private void startReleaseWatcher(MinecraftServer restoreServer, WorldOperationSession.Token token) {
        ReleasePoller poller = new ReleasePoller(restoreServer, token);
        releasePoller = poller;
        poller.future = coordinator.scheduleAtFixedRate(
                poller,
                100L,
                100L,
                TimeUnit.MILLISECONDS);
    }

    private void handleRejoinWorld(Map<String, String> fields) {
        var rejoin = operations.beginRejoin(fields);
        if (rejoin == null) return;
        var token = operations.sessionToken();
        var presentation = operations.activePresentation();
        String world = fields.getOrDefault("world", "");
        String backup = fields.getOrDefault("file", "");
        ClientHooks.requestRejoin(rejoin, new RestoreUiMessages(
                feedback.resolve(presentation, MessageSlot.RESTORE_REJOIN,
                        Component.translatable("minebackup.message.restore.rejoining"), world, backup),
                feedback.resolve(presentation, MessageSlot.RESTORE_SUCCEEDED,
                        Component.translatable("minebackup.message.restore.success_overlay"), world, backup)),
                () -> operations.isCurrent(token),
                (success, reason) -> operations.completeClientRejoin(token, success, reason));
    }

    private void broadcastInformationalEventOnServer(
            MinecraftServer currentServer,
            Map<String, String> fields,
            String event,
            com.leafuke.minebackup.api.v2.OperationPresentation presentation) {
        Component world = literalOrUnknown(
                firstNonBlank(fields.get("world"), fields.get("folder")),
                "minebackup.message.unknown_world");
        Component file = literalOrUnknown(fields.get("file"), "minebackup.message.unknown_file");
        Component error = FailureMessages.message(OperationFailure.Code.BACKEND_REJECTED);
        if (fields.get("error") != null || "backup_failed".equals(event) || "restore_failed".equals(event)) {
            MineBackup.LOGGER.warn("Backend event {} failed: {}", event, fields);
        }
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
            if (presentation.feedbackPolicy()
                    != com.leafuke.minebackup.api.v2.FeedbackPolicy.CALLER_MANAGED) {
                feedback.broadcastOnServer(
                        currentServer,
                        feedback.resolve(
                                presentation,
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
        automaticBackups.close();
        operations.close();
        // close() can run from SERVER_STOPPING, after Minecraft has rejected
        // all new server-thread tasks. Restoring the noSave flags is a local,
        // synchronous cleanup and must never enqueue work during shutdown.
        autoSave.unfreeze();
        knotLink.close();
        cancelSidecar(null);
        sidecarWorker.shutdownNow();
        coordinator.shutdownNow();
    }

    @Override
    public int apiVersion() {
        return MineBackupApi.API_VERSION;
    }

    @Override
    public OperationHandle<BackupResult> backupCurrent(BackupRequest request) {
        OperationHandle<BackupResult> handle = operations.backupCurrent(request);
        automaticBackups.observeExternalBackup(handle);
        return handle;
    }

    @Override
    public com.leafuke.minebackup.api.v2.OperationHandle<com.leafuke.minebackup.api.v2.ProtectedBackupResult> backupProtectedCurrent(BackupRequest request) {
        var handle = operations.startBackup(request, true);
        automaticBackups.observeExternalBackup(handle);
        return handle.protectedView();
    }

    @Override
    public com.leafuke.minebackup.api.v2.OperationHandle<com.leafuke.minebackup.api.v2.BackupProtectionResult> setBackupProtection(
            com.leafuke.minebackup.api.v2.BackupProtectionRequest request) { return operations.protection(request, true); }

    @Override
    public java.util.concurrent.CompletionStage<com.leafuke.minebackup.api.v2.BackupProtectionResult> queryBackupProtection(
            com.leafuke.minebackup.api.v2.BackupProtectionRequest request) { return operations.protection(request, false).completion(); }

    @Override
    public OperationHandle<RestoreResult> restoreCurrent(RestoreRequest request) {
        if (dedicatedServer && operationsAvailable) {
            DedicatedRestoreManager.Availability availability = dedicatedRestore.availability(
                    Config.get().dedicatedRestore(),
                    Path.of("").toAbsolutePath());
            if (!availability.available()) {
                return operations.rejectRestoreRequest(
                        request,
                        OperationFailure.Code.RESTART_UNAVAILABLE,
                        availability.reason());
            }
        }
        return operations.restoreCurrent(request);
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
        return legacyResult(automaticBackups.start(interval, CurrentWorldAutomationMode.BACKUP));
    }

    public AutoBackupUpdateResult stopAutomaticBackup() {
        return legacyResult(automaticBackups.stop());
    }

    public AutomationUpdateResult startCurrentWorldAutomation(
            Duration interval,
            CurrentWorldAutomationMode mode) {
        return automaticBackups.start(interval, mode);
    }

    public AutomationUpdateResult stopCurrentWorldAutomation() {
        return automaticBackups.stop();
    }

    public AutoBackupState automaticBackupState() {
        return automaticBackups.state();
    }

    @Override
    public CurrentWorldAutomationState currentWorldAutomation() {
        return automaticBackups.automationState();
    }

    @Override
    public CompletionStage<BackupCatalogResult> listCurrentBackups(BackupCatalogRequest request) {
        return operations.listCurrentBackups(request);
    }

    private final BackendQueries sharedBackendQueries = new BackendQueries(knotLink::query,
            () -> operationsAvailable ? server : null, knotLink::signalChannelState, knotLink::capabilityEpoch);
    { operations.capabilitySource(sharedBackendQueries::capabilities); }

    private BackendQueries backendQueries() { return sharedBackendQueries; }

    @Override
    public CompletionStage<com.leafuke.minebackup.api.v2.BackendCapabilitiesResult> backendCapabilities(
            com.leafuke.minebackup.api.v2.BackendCapabilitiesRequest request) {
        java.util.Objects.requireNonNull(request, "request");
        return backendQueries().capabilities(request.refresh());
    }

    @Override
    public CompletionStage<com.leafuke.minebackup.api.v2.BackendStatusResult> backendStatus(
            com.leafuke.minebackup.api.v2.BackendStatusRequest request) {
        java.util.Objects.requireNonNull(request, "request");
        return backendQueries().status();
    }

    @Override
    public com.leafuke.minebackup.api.v2.RestoreCancelResult cancelRestore(
            com.leafuke.minebackup.api.v2.RestoreCancelRequest request) {
        var result = operations.cancelRestore(request);
        MineBackup.LOGGER.debug("Restore cancellation by {} for {}: {}",
                request.callerId(), request.requestId(), result);
        return result;
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
                        handle instanceof RestoreOperationHandle restore ? restore.request().presentation()
                                : com.leafuke.minebackup.api.v2.OperationPresentation.defaults(),
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
                .withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(
                        Component.translatable(translationKey)))
                .withUnderlined(true));
    }

    private void broadcastAutomationReminder() {
        MinecraftServer current = server;
        if (current == null) {
            return;
        }
        current.executeIfPossible(() -> {
            if (server != current) {
                return;
            }
            var players = current.getPlayerList().getPlayers();
            if (players.isEmpty()) {
                MineBackup.LOGGER.info(
                        "Current-world backup reminder became due with no players online.");
                return;
            }
            for (ServerPlayer player : players) {
                MutableComponent message = Component.translatable(
                        "minebackup.message.auto.reminder");
                if (CommandPermissions.canUse(
                        player.createCommandSourceStack(), CommandCapability.BACKUP)) {
                    message.append(Component.literal(" "));
                    message.append(actionLink(
                            "minebackup.message.auto.reminder.backup",
                            "/mb backup"));
                }
                player.sendSystemMessage(message);
            }
        });
    }

    private static AutoBackupUpdateResult legacyResult(AutomationUpdateResult result) {
        CurrentWorldAutomationState state = result.state();
        AutoBackupState legacy = state.mode() == CurrentWorldAutomationMode.BACKUP
                ? new AutoBackupState(true, state.interval(), state.nextRun())
                : AutoBackupState.disabled();
        return new AutoBackupUpdateResult(result.success(), legacy, result.failure());
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
        private final WorldOperationSession.Token token;
        private final AtomicInteger readyStreak = new AtomicInteger();
        private volatile ScheduledFuture<?> future;
        private volatile boolean serverStopped;
        private volatile boolean playersCleared;

        private ReleasePoller(MinecraftServer restoreServer, WorldOperationSession.Token token) {
            this.restoreServer = restoreServer;
            this.token = token;
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
            if (!operations.isReleasing(token)) {
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
            if (!operations.markWorldReleased(token)) {
                cancel();
                return;
            }

            cancel();
            knotLink.query(KnotLinkRequest.command("WORLD_SAVE_AND_EXIT_COMPLETE").conversation(token.operationId()))
                    .whenComplete((response, error) -> {
                        if (!operations.isCurrent(token) || uncertainTransport(error)) return;
                        if (error == null && response != null && response.isOk()) {
                            return;
                        }
                        if (error != null) {
                            MineBackup.LOGGER.error(
                                    "Failed to acknowledge world release to the backend",
                                    error);
                        } else {
                            MineBackup.LOGGER.error(
                                    "Backend rejected world release acknowledgement: {}",
                                    (response == null ? "Missing backend response" : response.displayMessage()));
                        }
                        operations.failSession(token,
                                error == null ? OperationFailure.Code.BACKEND_REJECTED
                                        : KnotLinkCommunicationException.failure(error).code(),
                                error == null
                                        ? (response == null ? "Missing backend response" : response.displayMessage())
                                        : error.getMessage());
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
