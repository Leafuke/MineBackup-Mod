package com.leafuke.minebackup.runtime;

import com.leafuke.minebackup.MineBackup;
import com.leafuke.minebackup.api.v1.AutoBackupResult;
import com.leafuke.minebackup.api.v1.AutoBackupState;
import com.leafuke.minebackup.api.v1.BackupRequest;
import com.leafuke.minebackup.api.v1.BackupResult;
import com.leafuke.minebackup.api.v1.OperationFailure;
import com.leafuke.minebackup.config.Config;

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

    private boolean serverActive;
    private boolean closed;
    private long generation;
    private ScheduledFuture<?> future;
    private Instant nextRun;

    AutoBackupScheduler(
            CurrentWorldOperationCoordinator operations,
            ScheduledExecutorService scheduler) {
        this(operations, scheduler, Clock.systemUTC());
    }

    AutoBackupScheduler(
            CurrentWorldOperationCoordinator operations,
            ScheduledExecutorService scheduler,
            Clock clock) {
        this.operations = operations;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    synchronized void serverStarted() {
        if (closed) {
            return;
        }
        serverActive = true;
        restartFromConfigLocked();
    }

    synchronized void serverStopped() {
        serverActive = false;
        generation++;
        cancelFutureLocked();
    }

    AutoBackupResult start(Duration interval) {
        int minutes = validateInterval(interval);
        if (!Config.setAutoBackup(minutes)) {
            return failure(OperationFailure.Code.CONFIG_WRITE_FAILED, "Failed to persist automatic backup");
        }
        synchronized (this) {
            if (!closed) {
                restartFromConfigLocked();
            }
            return success(stateLocked());
        }
    }

    AutoBackupResult stop() {
        if (!Config.clearAutoBackup()) {
            return failure(OperationFailure.Code.CONFIG_WRITE_FAILED, "Failed to persist automatic backup");
        }
        synchronized (this) {
            generation++;
            cancelFutureLocked();
            return success(AutoBackupState.disabled());
        }
    }

    synchronized AutoBackupState state() {
        return stateLocked();
    }

    private synchronized void restartFromConfigLocked() {
        generation++;
        cancelFutureLocked();
        Config.AutoBackup config = Config.get().autoBackup();
        if (!serverActive || config == null || closed) {
            return;
        }
        scheduleLocked(config.intervalMinutes(), generation);
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
                        Config.AutoBackup current = Config.get().autoBackup();
                        if (!closed
                                && serverActive
                                && expectedGeneration == generation
                                && current != null
                                && current.intervalMinutes() == minutes) {
                            scheduleLocked(minutes, expectedGeneration);
                        }
                    }
                });
    }

    private synchronized AutoBackupState stateLocked() {
        Config.AutoBackup config = Config.get().autoBackup();
        if (config == null) {
            return AutoBackupState.disabled();
        }
        return new AutoBackupState(
                true,
                Optional.of(Duration.ofMinutes(config.intervalMinutes())),
                Optional.ofNullable(nextRun));
    }

    private AutoBackupResult success(AutoBackupState state) {
        return new AutoBackupResult(true, state, Optional.empty());
    }

    private AutoBackupResult failure(OperationFailure.Code code, String message) {
        return new AutoBackupResult(
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

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        serverActive = false;
        generation++;
        cancelFutureLocked();
    }
}
