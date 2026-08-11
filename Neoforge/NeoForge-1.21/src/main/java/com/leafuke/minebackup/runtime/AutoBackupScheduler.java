package com.leafuke.minebackup.runtime;

import com.leafuke.minebackup.MineBackup;
import com.leafuke.minebackup.api.v2.AutoBackupState;
import com.leafuke.minebackup.api.v2.BackupRequest;
import com.leafuke.minebackup.api.v2.BackupResult;
import com.leafuke.minebackup.api.v2.CurrentWorldAutomationMode;
import com.leafuke.minebackup.api.v2.CurrentWorldAutomationState;
import com.leafuke.minebackup.api.v2.OperationFailure;
import com.leafuke.minebackup.api.v2.OperationHandle;
import com.leafuke.minebackup.config.Config;
import com.leafuke.minebackup.config.WorldAutomationConfigStore;
import com.leafuke.minebackup.config.WorldIdentity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

final class AutoBackupScheduler implements AutoCloseable {
    private static final String CALLER_ID = "minebackup:auto";

    private final CurrentWorldOperationCoordinator operations;
    private final ScheduledExecutorService scheduler;
    private final Clock clock;
    private final WorldAutomationConfigStore configStore;
    private final Path gameDirectory;
    private final Runnable reminderNotifier;

    private boolean serverActive;
    private boolean closed;
    private long generation;
    private ScheduledFuture<?> future;
    private Instant nextRun;
    private WorldIdentity activeWorld;
    private WorldAutomationConfigStore.Settings settings =
            WorldAutomationConfigStore.Settings.off();

    AutoBackupScheduler(
            CurrentWorldOperationCoordinator operations,
            ScheduledExecutorService scheduler,
            Runnable reminderNotifier) {
        this(
                operations,
                scheduler,
                Clock.systemUTC(),
                new WorldAutomationConfigStore(Config.worldAutomationDirectory()),
                FMLPaths.GAMEDIR.get(),
                reminderNotifier);
    }

    AutoBackupScheduler(
            CurrentWorldOperationCoordinator operations,
            ScheduledExecutorService scheduler,
            Clock clock,
            WorldAutomationConfigStore configStore,
            Path gameDirectory,
            Runnable reminderNotifier) {
        this.operations = operations;
        this.scheduler = scheduler;
        this.clock = clock;
        this.configStore = configStore;
        this.gameDirectory = gameDirectory;
        this.reminderNotifier = reminderNotifier;
    }

    synchronized StartupResult serverStarted(MinecraftServer server) {
        if (closed) {
            return new StartupResult(WorldAutomationConfigStore.Migration.NONE, null);
        }
        generation++;
        cancelFutureLocked();
        try {
            activeWorld = WorldIdentity.resolve(
                    gameDirectory,
                    server.getWorldPath(LevelResource.ROOT),
                    server.getWorldData().getLevelName());
        } catch (IOException exception) {
            MineBackup.LOGGER.error("Failed to identify the current world for automation", exception);
            serverActive = true;
            activeWorld = null;
            settings = WorldAutomationConfigStore.Settings.off();
            return new StartupResult(WorldAutomationConfigStore.Migration.WORLD_LOAD_FAILED, null);
        }

        WorldAutomationConfigStore.LoadResult loaded = configStore.load(activeWorld);
        settings = loaded.settings();
        WorldAutomationConfigStore.Migration migration = loaded.valid()
                ? migrateLegacyLocked()
                : WorldAutomationConfigStore.Migration.WORLD_LOAD_FAILED;
        if (migration == WorldAutomationConfigStore.Migration.WORLD_WRITE_FAILED) {
            settings = WorldAutomationConfigStore.Settings.off();
        }
        serverActive = true;
        restartLocked();
        return new StartupResult(migration, activeWorld.displayName());
    }

    synchronized void serverStopped() {
        serverActive = false;
        generation++;
        cancelFutureLocked();
        activeWorld = null;
        settings = WorldAutomationConfigStore.Settings.off();
    }

    synchronized AutomationUpdateResult start(
            Duration interval,
            CurrentWorldAutomationMode mode) {
        int minutes = validateInterval(interval);
        if (mode == null || mode == CurrentWorldAutomationMode.OFF) {
            throw new IllegalArgumentException("Automation start requires BACKUP or REMIND mode");
        }
        if (!serverActive || activeWorld == null || closed) {
            return failure(OperationFailure.Code.NO_ACTIVE_SERVER, "No active world for automation");
        }
        WorldAutomationConfigStore.Settings updated = mode == CurrentWorldAutomationMode.BACKUP
                ? WorldAutomationConfigStore.Settings.backup(minutes)
                : WorldAutomationConfigStore.Settings.remind(minutes);
        if (!configStore.write(activeWorld, updated)) {
            return failure(OperationFailure.Code.CONFIG_WRITE_FAILED, "Failed to persist world automation");
        }
        settings = updated;
        restartLocked();
        return success(automationStateLocked());
    }

    synchronized AutomationUpdateResult stop() {
        if (!serverActive || activeWorld == null || closed) {
            return failure(OperationFailure.Code.NO_ACTIVE_SERVER, "No active world for automation");
        }
        WorldAutomationConfigStore.Settings updated = WorldAutomationConfigStore.Settings.off();
        if (!configStore.write(activeWorld, updated)) {
            return failure(OperationFailure.Code.CONFIG_WRITE_FAILED, "Failed to persist world automation");
        }
        settings = updated;
        generation++;
        cancelFutureLocked();
        return success(automationStateLocked());
    }

    synchronized CurrentWorldAutomationState automationState() {
        return automationStateLocked();
    }

    synchronized AutoBackupState state() {
        if (settings.mode() != WorldAutomationConfigStore.Mode.BACKUP) {
            return AutoBackupState.disabled();
        }
        return new AutoBackupState(
                true,
                Optional.of(Duration.ofMinutes(settings.intervalMinutes())),
                Optional.ofNullable(nextRun));
    }

    void observeExternalBackup(OperationHandle<BackupResult> handle) {
        WorldIdentity world;
        long expectedGeneration;
        synchronized (this) {
            world = activeWorld;
            expectedGeneration = generation;
        }
        if (world == null) {
            return;
        }
        handle.completion().whenComplete((result, error) -> {
            if (error == null && isEffectiveBackup(result)) {
                resetAfterEffectiveBackup(world, expectedGeneration);
            }
        });
    }

    private void restartLocked() {
        generation++;
        cancelFutureLocked();
        if (!serverActive || !settings.active() || closed) {
            return;
        }
        scheduleLocked(settings, activeWorld, generation);
    }

    private void scheduleLocked(
            WorldAutomationConfigStore.Settings expectedSettings,
            WorldIdentity expectedWorld,
            long expectedGeneration) {
        if (!serverActive || closed || expectedGeneration != generation
                || !expectedSettings.equals(settings) || !expectedWorld.equals(activeWorld)) {
            return;
        }
        int minutes = expectedSettings.intervalMinutes();
        nextRun = clock.instant().plus(Duration.ofMinutes(minutes));
        try {
            future = scheduler.schedule(
                    () -> runDue(expectedGeneration, expectedWorld, expectedSettings),
                    minutes,
                    TimeUnit.MINUTES);
        } catch (RejectedExecutionException exception) {
            future = null;
            nextRun = null;
            MineBackup.LOGGER.warn("Current-world automation scheduler rejected a task", exception);
        }
    }

    private void runDue(
            long expectedGeneration,
            WorldIdentity expectedWorld,
            WorldAutomationConfigStore.Settings expectedSettings) {
        synchronized (this) {
            if (!matchesLocked(expectedGeneration, expectedWorld, expectedSettings)) {
                return;
            }
            future = null;
            nextRun = null;
        }

        if (expectedSettings.mode() == WorldAutomationConfigStore.Mode.REMIND) {
            try {
                reminderNotifier.run();
            } catch (RuntimeException exception) {
                MineBackup.LOGGER.warn("Failed to send automatic backup reminder", exception);
            }
            rearmIfCurrent(expectedGeneration, expectedWorld, expectedSettings);
            return;
        }

        operations.backupCurrent(BackupRequest.create(CALLER_ID))
                .completion()
                .whenComplete((result, error) -> {
                    if (error != null) {
                        MineBackup.LOGGER.warn("Automatic hot backup handle failed", error);
                    } else if (result.outcome() == BackupResult.Outcome.REJECTED
                            && result.failure()
                            .map(OperationFailure::code)
                            .filter(code -> code == OperationFailure.Code.BUSY)
                            .isPresent()) {
                        MineBackup.LOGGER.info(
                                "Skipped automatic hot backup because another current-world operation is active.");
                    } else if (!isEffectiveBackup(result)) {
                        MineBackup.LOGGER.warn(
                                "Automatic hot backup ended with outcome {}: {}",
                                result.outcome(),
                                result.failure().map(OperationFailure::message).orElse("no details"));
                    }
                    if (error == null && isEffectiveBackup(result)) {
                        resetAfterEffectiveBackup(expectedWorld, expectedGeneration);
                    } else {
                        rearmIfCurrent(expectedGeneration, expectedWorld, expectedSettings);
                    }
                });
    }

    private synchronized void rearmIfCurrent(
            long expectedGeneration,
            WorldIdentity expectedWorld,
            WorldAutomationConfigStore.Settings expectedSettings) {
        if (matchesLocked(expectedGeneration, expectedWorld, expectedSettings)) {
            scheduleLocked(expectedSettings, expectedWorld, expectedGeneration);
        }
    }

    private synchronized void resetAfterEffectiveBackup(
            WorldIdentity expectedWorld,
            long expectedGeneration) {
        if (!closed && serverActive && expectedGeneration == generation
                && expectedWorld.equals(activeWorld) && settings.active()) {
            restartLocked();
        }
    }

    private boolean matchesLocked(
            long expectedGeneration,
            WorldIdentity expectedWorld,
            WorldAutomationConfigStore.Settings expectedSettings) {
        return !closed && serverActive && expectedGeneration == generation
                && expectedWorld.equals(activeWorld) && expectedSettings.equals(settings);
    }

    private CurrentWorldAutomationState automationStateLocked() {
        if (!serverActive || activeWorld == null) {
            return CurrentWorldAutomationState.unavailable();
        }
        if (!settings.active()) {
            return CurrentWorldAutomationState.disabled(activeWorld.displayName());
        }
        return new CurrentWorldAutomationState(
                true,
                Optional.of(activeWorld.displayName()),
                apiMode(settings.mode()),
                Optional.of(Duration.ofMinutes(settings.intervalMinutes())),
                Optional.ofNullable(nextRun));
    }

    private static CurrentWorldAutomationMode apiMode(WorldAutomationConfigStore.Mode mode) {
        return switch (mode) {
            case OFF -> CurrentWorldAutomationMode.OFF;
            case BACKUP -> CurrentWorldAutomationMode.BACKUP;
            case REMIND -> CurrentWorldAutomationMode.REMIND;
        };
    }

    private static boolean isEffectiveBackup(BackupResult result) {
        return result != null && (result.outcome() == BackupResult.Outcome.CREATED
                || result.outcome() == BackupResult.Outcome.NO_CHANGES);
    }

    private AutomationUpdateResult success(CurrentWorldAutomationState state) {
        return new AutomationUpdateResult(true, state, Optional.empty());
    }

    private AutomationUpdateResult failure(OperationFailure.Code code, String message) {
        return new AutomationUpdateResult(
                false,
                automationState(),
                Optional.of(new OperationFailure(code, message)));
    }

    static int validateInterval(Duration interval) {
        if (interval == null || interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("Automation interval must be positive");
        }
        long minutes = interval.toMinutes();
        if (!interval.equals(Duration.ofMinutes(minutes))
                || minutes < 1
                || minutes > Config.MAX_AUTO_BACKUP_INTERVAL_MINUTES) {
            throw new IllegalArgumentException(
                    "Automation interval must be a whole number of supported minutes");
        }
        return Math.toIntExact(minutes);
    }

    private void cancelFutureLocked() {
        ScheduledFuture<?> current = future;
        future = null;
        nextRun = null;
        if (current != null) {
            current.cancel(false);
        }
    }

    private WorldAutomationConfigStore.Migration migrateLegacyLocked() {
        Config.AutoBackup legacy = Config.get().autoBackup();
        WorldAutomationConfigStore.MigrationResult migration = configStore.migrateLegacyBackup(
                activeWorld,
                settings,
                legacy == null ? null : legacy.intervalMinutes(),
                Config::clearLegacyCurrentWorldAutoBackup);
        settings = migration.settings();
        return migration.migration();
    }

    synchronized void activateForTest(
            WorldIdentity world,
            WorldAutomationConfigStore.Settings newSettings) {
        activeWorld = world;
        settings = newSettings;
        serverActive = true;
        restartLocked();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        serverActive = false;
        generation++;
        cancelFutureLocked();
        activeWorld = null;
        settings = WorldAutomationConfigStore.Settings.off();
    }

    record StartupResult(WorldAutomationConfigStore.Migration migration, String worldName) {
    }
}
