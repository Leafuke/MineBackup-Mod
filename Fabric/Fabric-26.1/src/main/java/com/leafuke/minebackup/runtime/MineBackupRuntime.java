package com.leafuke.minebackup.runtime;

import com.leafuke.minebackup.MineBackup;
import com.leafuke.minebackup.api.v2.AutoBackupState;
import com.leafuke.minebackup.api.v2.BackupCatalogRequest;
import com.leafuke.minebackup.api.v2.BackupCatalogResult;
import com.leafuke.minebackup.api.v2.BackupRequest;
import com.leafuke.minebackup.api.v2.BackupResult;
import com.leafuke.minebackup.api.v2.CurrentWorldAutomationMode;
import com.leafuke.minebackup.api.v2.CurrentWorldAutomationState;
import com.leafuke.minebackup.api.v2.MessageSlot;
import com.leafuke.minebackup.api.v2.MineBackupApi;
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
import com.leafuke.minebackup.interop.*;
import com.leafuke.minebackup.interop.InteropSnapshot.Ack;
import com.leafuke.minebackup.knotlink.KnotLinkCommunicationException;
import com.leafuke.minebackup.restore.RestoreSession;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

public final class MineBackupRuntime implements MineBackupApi, AutoCloseable {

    private final InteropClient link =
            new InteropClient(
                    Config.restartDirectory().resolveSibling("interop-v3"),
                    new InteropClient.Listener() {
                        public void snapshot(InteropSnapshot value, boolean visible) {
                            onSnapshot(value, visible);
                        }

                        public void actionAcknowledged(UUID operation, String kind) {
                            if (!kind.equals("open")) return;
                            Exit previous = exit;
                            if (previous != null && previous.operation.equals(operation)) {
                                exit = null;
                                MinecraftServer joined = server;
                                if (joined != null)
                                    joined.executeIfPossible(
                                            () ->
                                                    link.enter(
                                                            joined.getWorldPath(LevelResource.ROOT),
                                                            joined.isDedicatedServer()));
                                else link.leave(false);
                            }
                        }

                        public void unconfirmed(UUID id, boolean restore) {
                            operations.unconfirmed(id);
                            knotLink.unconfirmed(id);
                        }

                        public CompletionStage<Ack> perform(
                                InteropClient.Binding b,
                                InteropSnapshot s,
                                long expiry,
                                BooleanSupplier current) {
                            return gameStep(b, s, expiry, current);
                        }
                    });
    private final InteropRequests knotLink = new InteropRequests(link);
    private volatile UUID frozenStep;
    private volatile long freezeUntil;
    private volatile Exit exit;

    private static final class Exit {
        final MinecraftServer original;
        final UUID operation;
        final Path root;
        final RestoreSession.RejoinInfo info;
        volatile boolean stopped, opening;
        volatile long startBefore;

        Exit(MinecraftServer server, UUID id, Path root, RestoreSession.RejoinInfo info) {
            original = server;
            operation = id;
            this.root = root;
            this.info = info;
        }
    }

    private final java.util.LinkedHashMap<UUID, Boolean> announced =
            new java.util.LinkedHashMap<>();
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

    private final java.util.concurrent.ExecutorService sidecarWorker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "minebackup-sidecar-start");
        thread.setDaemon(true);
        return thread;
    });

    {
        operations.bindLifecycle(link::signalConnected, (token, p, f, done) -> done.run());
        operations.useInterop(knotLink);
    }

    private final AutoBackupScheduler automaticBackups =
            new AutoBackupScheduler(operations, coordinator, this::broadcastAutomationReminder);
    private final DedicatedRestoreManager dedicatedRestore =
            new DedicatedRestoreManager(Config.restartDirectory());

    private volatile MinecraftServer server;
    private final FeedbackRouter feedback = new FeedbackRouter(() -> server);
    private volatile boolean operationsAvailable;
    private volatile boolean dedicatedServer;



    public void registerEvents() {
        ServerLifecycleEvents.SERVER_STARTING.register(this::onServerStarting);
        ServerLifecycleEvents.SERVER_STARTED.register(
                current -> {
                    if (current.isDedicatedServer())
                        link.enter(current.getWorldPath(LevelResource.ROOT), true);
                });
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
        ServerLifecycleEvents.SERVER_STOPPED.register(this::onServerStopped);
        ServerTickEvents.END_SERVER_TICK.register(
                current -> {
                    if (frozenStep != null
                            && (System.nanoTime() >= freezeUntil || !autoSave.continuous(current)))
                        clearFreeze();
                });
        ServerPlayConnectionEvents.JOIN.register(this::onPlayerJoin);
    }

    public InteropRequests knotLink() {
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

        Config.load();
        Path root = startingServer.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        Exit pending = exit;
        boolean expected = pending != null && pending.opening && pending.root.equals(root);
        if (!expected) {
            exit = null;
            operations.worldStarted(root);
            if (!dedicatedServer) link.enter(root, false);
        }
        sidecarWorker.execute(dedicatedRestore::loadLastResult);

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
        operations.serverStopping(exit != null && exit.original == stoppingServer);
        link.leave(exit != null && exit.original == stoppingServer);
        clearFreeze();

        if (dedicatedServer) {
            close();
        }
    }

    private void onServerStopped(MinecraftServer stoppedServer) {
        Exit pending = exit;
        if (pending != null && pending.original == stoppedServer)
            pending.stopped = stoppedServer.getPlayerList().getPlayers().isEmpty();
        if (server == stoppedServer) {
            server = null;
            operationsAvailable = false;
        }
    }

    private void clearFreeze() {
        UUID step = frozenStep;
        if (step != null) autoSave.unfreeze(step);
        frozenStep = null;
    }

    private synchronized void onSnapshot(InteropSnapshot s, boolean visible) {
        var presentation =
                operations
                        .activeSnapshot()
                        .filter(h -> h.requestId().equals(s.operation()))
                        .map(h -> operations.activePresentation())
                        .orElse(com.leafuke.minebackup.api.v2.OperationPresentation.defaults());
        knotLink.observed(s);
        operations.observe(s);
        if (!visible || !Set.of("backup", "restore", "backupAll").contains(s.kind())) return;
        Boolean terminalSeen = announced.get(s.operation());
        if (Boolean.TRUE.equals(terminalSeen)) return;
        if (announced.size() >= 2048 && terminalSeen == null) announced.pollFirstEntry();
        if (s.state().equals("queued") || s.state().equals("running")) {
            if (announced.put(s.operation(), false) == null)
                feedback.optional(
                        presentation,
                        s.kind().equals("restore")
                                ? MessageSlot.RESTORE_PREPARING
                                : MessageSlot.BACKUP_STARTED,
                        Component.translatable("minebackup.interop.queued"));
        } else if (s.result() != null) {
            announced.put(s.operation(), true);
            boolean ok = s.result().get("outcome").getAsString().equals("succeeded");
            if (ok)
                feedback.optional(
                        presentation,
                        s.kind().equals("restore")
                                ? MessageSlot.RESTORE_SUCCEEDED
                                : MessageSlot.BACKUP_SUCCEEDED,
                        Component.translatable(
                                s.kind().equals("restore")
                                        ? "minebackup.interop.restored"
                                        : s.warnings()
                                                ? "minebackup.interop.backup_warning"
                                                : s.result().has("effect")
                                                                && s.result()
                                                                        .get("effect")
                                                                        .getAsString()
                                                                        .equals("unchanged")
                                                        ? "minebackup.message.backup.no_changes"
                                                        : "minebackup.interop.backed_up"));
        }
        MineBackup.LOGGER.debug(
                "Interop operation {} revision {} state {}", s.operation(), s.rev(), s.state());
    }

    private CompletionStage<Ack> gameStep(
            InteropClient.Binding b, InteropSnapshot snap, long deadline, BooleanSupplier live) {
        var answer = new CompletableFuture<Ack>();
        var step = snap.step();
        if (step.kind().equals("open")) {
            Exit pending = exit;
            if (pending == null
                    || !pending.operation.equals(snap.operation())
                    || !pending.stopped
                    || server != null) {
                answer.complete(Ack.of("abandoned"));
                return answer;
            }
            pending.opening = true;
            pending.startBefore = deadline;
            ClientHooks.requestRejoin(
                    pending.info,
                    new RestoreUiMessages(
                            Component.translatable("minebackup.message.restore.rejoining"),
                            Component.translatable("minebackup.message.restore.success_overlay")),
                    () -> live.getAsBoolean() && exit == pending,
                    (ok, reason) -> {
                        boolean correct =
                                ok
                                        && server != null
                                        && server.getWorldPath(LevelResource.ROOT)
                                                .toAbsolutePath()
                                                .normalize()
                                                .equals(pending.root);
                        answer.complete(Ack.of(correct ? "opened" : "failed"));
                    });
            return answer;
        }
        MinecraftServer current = server;
        if (current == null || !operationsAvailable) {
            answer.complete(Ack.of("abandoned"));
            return answer;
        }
        current.executeIfPossible(
                () -> {
                    try {
                        if (current != server
                                || !live.getAsBoolean()
                                || System.nanoTime() >= deadline) {
                            answer.complete(Ack.of("abandoned"));
                            return;
                        }
                        switch (step.kind()) {
                            case "saveAndFreeze" -> {
                                if (frozenStep != null
                                        || !LocalSaveCoordinator.save(current)
                                        || !live.getAsBoolean() || System.nanoTime()>=deadline
                                        || !autoSave.freeze(current, step.id())) {
                                    answer.complete(Ack.of("failed"));
                                    return;
                                }
                                frozenStep = step.id();
                                freezeUntil =
                                        System.nanoTime()
                                                + java.time.Duration.ofSeconds(180).toNanos();
                                answer.complete(Ack.of("frozen"));
                            }
                            case "thaw" -> {
                                boolean continuous =
                                        step.releases().equals(frozenStep)
                                                && System.nanoTime() < freezeUntil
                                                && autoSave.continuous(current);
                                if (step.releases().equals(frozenStep)) clearFreeze();
                                answer.complete(new Ack("thawed", continuous, null));
                            }
                            case "saveAndExit" -> {
                                if (exit != null
                                        || frozenStep != null
                                        || !LocalSaveCoordinator.save(current)) {
                                    answer.complete(Ack.of("failed"));
                                    return;
                                }
                                var lan = captureLanState(current);
                                Path root =
                                        current.getWorldPath(LevelResource.ROOT)
                                                .toAbsolutePath()
                                                .normalize();
                                Exit pending =
                                        new Exit(
                                                current,
                                                snap.operation(),
                                                root,
                                                new RestoreSession.RejoinInfo(
                                                        root.getFileName().toString(),
                                                        lan.reopen(),
                                                        lan.port()));
                                if (!dedicatedServer) {
                                    // Persist the local close fence before any player is
                                    // disconnected or release is acknowledged.
                                    sidecarWorker.execute(
                                            () -> {
                                                try {
                                                    link.rememberExit(root, b, snap.operation());
                                                } catch (Exception failure) {
                                                    answer.complete(Ack.of("failed"));
                                                    return;
                                                }
                                                current.executeIfPossible(
                                                        () -> {
                                                            if (server != current
                                                                    || !live.getAsBoolean()) {
                                                                answer.complete(
                                                                        Ack.of("abandoned"));
                                                                return;
                                                            }
                                                            beginV3Release(
                                                                    current, pending, root, answer,
                                                                    live);
                                                        });
                                            });
                                } else {
                                    prepareV3Sidecar(current, b, snap, pending, answer, ()->live.getAsBoolean() && System.nanoTime()<deadline);
                                }
                            }
                            default -> answer.complete(Ack.of("abandoned"));
                        }
                    } catch (RuntimeException error) {
                        answer.completeExceptionally(error);
                    }
                });
        return answer;
    }

    private void beginV3Release(
            MinecraftServer current,
            Exit pending,
            Path root,
            CompletableFuture<Ack> answer,
            BooleanSupplier live) {
        exit = pending;
        disconnectPlayers(current, Component.translatable("minebackup.message.restore.kick"));
        sidecarWorker.execute(
                () -> {
                    int stable = 0;
                    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                    try {
                        while (System.nanoTime() < end && live.getAsBoolean() && exit == pending) {
                            stable =
                                    pending.stopped && WorldReleaseProbe.isReleased(root)
                                            ? stable + 1
                                            : 0;
                            if (stable >= 3) {
                                answer.complete(Ack.of("released"));
                                return;
                            }
                            Thread.sleep(100);
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    answer.complete(Ack.of("failed"));
                });
    }

    public ExitPermission openingPermission(Path root) {
        var b = link.binding();
        var pending = exit;
        if (pending == null
                || !pending.opening
                || b == null
                || !pending.root.equals(root.toAbsolutePath().normalize())) return null;
        return new ExitPermission(pending.operation, b.endpoint().domain(), pending.startBefore);
    }

    public record ExitPermission(UUID operation, UUID domain, long startBefore) {}

    private void prepareV3Sidecar(
            MinecraftServer current,
            InteropClient.Binding binding,
            InteropSnapshot snap,
            Exit pending,
            CompletableFuture<Ack> answer,
            BooleanSupplier live) {
        var config = Config.get().dedicatedRestore();
        Path cwd = Path.of("").toAbsolutePath();
        var available = dedicatedRestore.availability(config, cwd);
        if (!available.available()) {
            answer.complete(Ack.of("failed"));
            return;
        }
        sidecarWorker.execute(
                () -> {
                    try {
                        var handoff =
                                dedicatedRestore.startInterop(
                                        config,
                                        cwd,
                                        pending.root,
                                        available.resolution().command(),
                                        binding,
                                        snap,
                                        () ->
                                                server == current
                                                        && live.getAsBoolean()
                                                        && operationsAvailable);
                        if(server!=current || !live.getAsBoolean() || !operationsAvailable || !handoff.commit()){
                            handoff.abandon();answer.complete(Ack.of("abandoned"));return;
                        }
                        current.executeIfPossible(
                                () -> {
                                    if (server != current
                                            || !live.getAsBoolean()
                                            || !operationsAvailable
                                            || !handoff.child().isAlive()) {
                                        handoff.abandon();
                                        answer.complete(Ack.of("abandoned"));
                                        return;
                                    }
                                    exit = pending;
                                    disconnectPlayers(
                                            current,
                                            Component.translatable(
                                                    "minebackup.message.restore.kick"));
                                    if (operations
                                            .activeSnapshot()
                                            .filter(s -> s.requestId().equals(snap.operation()))
                                            .isPresent()) operations.completeDedicatedHandoff();
                                    operationsAvailable = false;
                                    current.halt(false);
                                    // released is owned by the committed child after parent JVM
                                    // exit.
                                });
                    } catch (Exception failure) {
                        answer.complete(Ack.of("failed"));
                    }
                });
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
        link.close();

        sidecarWorker.shutdownNow();
        coordinator.shutdownNow();
    }

    @Override
    public CompletionStage<com.leafuke.minebackup.api.v2.LinkedOperationStatus> observeOperation(
            UUID domain, UUID id) {
        return link.observe(domain, id)
                .handle(
                        (s, err) -> {
                            if (err != null)
                                return new com.leafuke.minebackup.api.v2.LinkedOperationStatus(
                                        domain,
                                        id,
                                        Optional.empty(),
                                        Optional.of(KnotLinkCommunicationException.failure(err)));
                            return new com.leafuke.minebackup.api.v2.LinkedOperationStatus(
                                    domain,
                                    id,
                                    Optional.of(
                                            new com.leafuke.minebackup.api.v2.LinkedOperationStatus
                                                    .Snapshot(
                                                    s.kind(),
                                                    s.target(),
                                                    s.rev(),
                                                    s.state(),
                                                    s.result() == null
                                                            ? Optional.empty()
                                                            : Optional.of(
                                                                    s.result()
                                                                            .get("outcome")
                                                                            .getAsString()),
                                                    Optional.ofNullable(s.disk()),
                                                    s.recoveryPending(),
                                                    Optional.ofNullable(s.reopen()),
                                                    Optional.ofNullable(s.followUp()))),
                                    Optional.empty());
                        });
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

    private final BackendQueries sharedBackendQueries =
            new BackendQueries(
                    knotLink::query,
                    () -> this,
                    () ->
                            link.signalConnected()
                                    ? com.leafuke.minebackup.api.v2.BackendStatusResult.ChannelState
                                            .CONNECTED
                                    : com.leafuke.minebackup.api.v2.BackendStatusResult.ChannelState
                                            .UNKNOWN);

    @Override
    public CompletionStage<com.leafuke.minebackup.api.v2.BackendCapabilitiesResult> backendCapabilities(
            com.leafuke.minebackup.api.v2.BackendCapabilitiesRequest request) {
        java.util.Objects.requireNonNull(request, "request");
        return request.refresh()
                ? link.refresh().thenCompose(status -> knotLink.capabilities())
                : knotLink.capabilities();
    }

    @Override
    public CompletionStage<com.leafuke.minebackup.api.v2.BackendStatusResult> backendStatus(
            com.leafuke.minebackup.api.v2.BackendStatusRequest request) {
        java.util.Objects.requireNonNull(request, "request");
        return sharedBackendQueries.status();
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
                        player.createCommandSourceStack(),
                        CommandCapability.BACKUP)) {
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

}
