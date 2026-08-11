package com.leafuke.minebackup.runtime;

import com.leafuke.minebackup.MineBackup;
import com.leafuke.minebackup.api.v2.AutoBackupState;
import com.leafuke.minebackup.api.v2.BackupRequest;
import com.leafuke.minebackup.api.v2.BackupResult;
import com.leafuke.minebackup.api.v2.OperationFailure;
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
            ScheduledExecutorService scheduler) {
        this(
                operations,
                scheduler,
                Clock.systemUTC(),
                new WorldAutomationConfigStore(Config.worldAutomationDirectory()),
                FMLPaths.GAMEDIR.get());
    }

    AutoBackupScheduler(
            CurrentWorldOperationCoordinator operations,
            ScheduledExecutorService scheduler,
            Clock clock,
            WorldAutomationConfigStore configStore,
            Path gameDirectory) {
        this.operations = operations;
        this.scheduler = scheduler;
        this.clock = clock;
        this.configStore = configStore;
        this.gameDirectory = gameDirectory;
    }

    synchronized StartupResult serverStarted(MinecraftServer server) {
        if (closed) {
            return new StartupResult(WorldAutomationConfigStore.Migration.NONE, null);
        }
        try {
            activeWorld = WorldIdentity.resolve(
                    gameDirectory,
                    server.getWorldPath(LevelResource.ROOT),
                    server.getWorldData().getLevelName());
        } catch (IOException exception) {
            MineBackup.LOGGER.error("Failed to identify the current world for automation", exception);
            serverActive = true;
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

    synchronized AutoBackupUpdateResult start(Duration interval) {
        int minutes = validateInterval(interval);
        if (!serverActive || activeWorld == null || closed) {
            return failure(OperationFailure.Code.NO_ACTIVE_SERVER, "No active world for automatic backup");
        }
        WorldAutomationConfigStore.Settings updated =
                WorldAutomationConfigStore.Settings.backup(minutes);
        if (!configStore.write(activeWorld, updated)) {
            return failure(OperationFailure.Code.CONFIG_WRITE_FAILED, "Failed to persist automatic backup");
        }
        settings = updated;
        restartLocked();
        return success(stateLocked());
    }

    synchronized AutoBackupUpdateResult stop() {
        if (!serverActive || activeWorld == null || closed) {
            return failure(OperationFailure.Code.NO_ACTIVE_SERVER, "No active world for automatic backup");
        }
        WorldAutomationConfigStore.Settings updated = WorldAutomationConfigStore.Settings.off();
        if (!configStore.write(activeWorld, updated)) {
            return failure(OperationFailure.Code.CONFIG_WRITE_FAILED, "Failed to persist automatic backup");
        }
        settings = updated;
        generation++;
        cancelFutureLocked();
        return success(AutoBackupState.disabled());
    }

    synchronized AutoBackupState state() {
        return stateLocked();
    }

    private void restartLocked() {
        generation++;
        cancelFutureLocked();
        if (!serverActive || !settings.active() || closed) {
            return;
        }
        scheduleLocked(settings.intervalMinutes(), generation);
    }

    private void scheduleLocked(int minutes, long expectedGeneration) {
        if (!serverActive || closed || expectedGeneration != generation) {
            return;
        }
        nextRun = clock.instant().plus(Duration.ofMinutes(minutes));
        try {
            future = scheduler.schedule(
                    () -> runDue(expectedGeneration, minutes),
                    minutes,
                    TimeUnit.MINUTES);
        } catch (RejectedExecutionException exception) {
            future = null;
            nextRun = null;
            MineBackup.LOGGER.warn("Automatic backup scheduler rejected a task", exception);
        }
    }

    private void runDue(long expectedGeneration, int minutes) {
        synchronized (this) {
            if (closed || !serverActive || expectedGeneration != generation) {
                return;
            }
            future = null;
            nextRun = null;
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
                    }
                    synchronized (AutoBackupScheduler.this) {
                        if (!closed
                                && serverActive
                                && expectedGeneration == generation
                                && settings.active()
                                && settings.intervalMinutes() == minutes) {
                            scheduleLocked(minutes, expectedGeneration);
                        }
                    }
                });
    }

    private synchronized AutoBackupState stateLocked() {
        if (!settings.active()) {
            return AutoBackupState.disabled();
        }
        return new AutoBackupState(
                true,
                Optional.of(Duration.ofMinutes(settings.intervalMinutes())),
                Optional.ofNullable(nextRun));
    }

    private AutoBackupUpdateResult success(AutoBackupState state) {
        return new AutoBackupUpdateResult(true, state, Optional.empty());
    }

    private AutoBackupUpdateResult failure(OperationFailure.Code code, String message) {
        return new AutoBackupUpdateResult(
                false,
                state(),
                Optional.of(new OperationFailure(code, message)));
    }

    static int validateInterval(Duration interval) {
        if (interval == null || interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("Automatic backup interval must be positive");
        }
        long minutes = interval.toMinutes();
        if (!interval.equals(Duration.ofMinutes(minutes))
                || minutes < 1
                || minutes > Config.MAX_AUTO_BACKUP_INTERVAL_MINUTES) {
            throw new IllegalArgumentException(
                    "Automatic backup interval must be a whole number of supported minutes");
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
