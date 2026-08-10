package com.leafuke.minebackup.runtime;

import com.leafuke.minebackup.api.v2.BackupResult;
import com.leafuke.minebackup.api.v2.CurrentWorldAutomationMode;
import com.leafuke.minebackup.api.v2.OperationHandle;
import com.leafuke.minebackup.api.v2.OperationPhase;
import com.leafuke.minebackup.config.WorldAutomationConfigStore;
import com.leafuke.minebackup.config.WorldIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoBackupSchedulerTest {
    private static final Instant NOW = Instant.parse("2026-08-10T10:00:00Z");

    @TempDir
    Path root;

    @Test
    void reminderDoesNotBackUpAndRearms() {
        RecordingScheduler executor = new RecordingScheduler();
        AtomicInteger reminders = new AtomicInteger();
        AutoBackupScheduler scheduler = scheduler(executor, reminders);
        scheduler.activateForTest(world("A"), WorldAutomationConfigStore.Settings.remind(15));

        executor.tasks.getFirst().run();

        assertEquals(1, reminders.get());
        assertEquals(2, executor.tasks.size());
        assertEquals(CurrentWorldAutomationMode.REMIND, scheduler.automationState().mode());
    }

    @Test
    void lateReminderFromPreviousWorldCannotRearmOrNotify() {
        RecordingScheduler executor = new RecordingScheduler();
        AtomicInteger reminders = new AtomicInteger();
        AutoBackupScheduler scheduler = scheduler(executor, reminders);
        scheduler.activateForTest(world("A"), WorldAutomationConfigStore.Settings.remind(15));
        Runnable stale = executor.tasks.getFirst();

        scheduler.activateForTest(world("B"), WorldAutomationConfigStore.Settings.remind(30));
        stale.run();

        assertEquals(0, reminders.get());
        assertEquals(2, executor.tasks.size());
        assertEquals("B", scheduler.automationState().worldName().orElseThrow());
    }

    @Test
    void successfulExternalBackupResetsCurrentWorldSchedule() {
        RecordingScheduler executor = new RecordingScheduler();
        AutoBackupScheduler scheduler = scheduler(executor, new AtomicInteger());
        scheduler.activateForTest(world("A"), WorldAutomationConfigStore.Settings.remind(15));
        CompletableFuture<BackupResult> completion = new CompletableFuture<>();
        scheduler.observeExternalBackup(handle(completion));

        completion.complete(new BackupResult(
                BackupResult.Outcome.NO_CHANGES, Optional.empty(), Optional.empty()));

        assertTrue(executor.futures.getFirst().cancelled);
        assertEquals(2, executor.tasks.size());
    }

    @Test
    void rejectedBackupAndOldWorldCompletionDoNotResetCurrentSchedule() {
        RecordingScheduler executor = new RecordingScheduler();
        AutoBackupScheduler scheduler = scheduler(executor, new AtomicInteger());
        scheduler.activateForTest(world("A"), WorldAutomationConfigStore.Settings.remind(15));
        CompletableFuture<BackupResult> rejected = new CompletableFuture<>();
        scheduler.observeExternalBackup(handle(rejected));
        rejected.complete(new BackupResult(
                BackupResult.Outcome.REJECTED, Optional.empty(), Optional.empty()));
        assertEquals(1, executor.tasks.size());

        CompletableFuture<BackupResult> stale = new CompletableFuture<>();
        scheduler.observeExternalBackup(handle(stale));
        scheduler.activateForTest(world("B"), WorldAutomationConfigStore.Settings.remind(20));
        stale.complete(new BackupResult(
                BackupResult.Outcome.NO_CHANGES, Optional.empty(), Optional.empty()));

        assertEquals(2, executor.tasks.size());
        assertFalse(executor.futures.get(1).cancelled);
    }

    private AutoBackupScheduler scheduler(
            RecordingScheduler executor,
            AtomicInteger reminders) {
        return new AutoBackupScheduler(
                null,
                executor,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new WorldAutomationConfigStore(root.resolve("config")),
                root,
                reminders::incrementAndGet);
    }

    private static WorldIdentity world(String name) {
        return new WorldIdentity("relative:saves/" + name, name, "key-" + name);
    }

    private static OperationHandle<BackupResult> handle(
            CompletableFuture<BackupResult> completion) {
        return new OperationHandle<>() {
            @Override
            public UUID id() {
                return UUID.randomUUID();
            }

            @Override
            public String callerId() {
                return "test";
            }

            @Override
            public OperationPhase phase() {
                return OperationPhase.RUNNING;
            }

            @Override
            public CompletableFuture<BackupResult> completion() {
                return completion;
            }
        };
    }

    private static final class RecordingScheduler extends ScheduledThreadPoolExecutor {
        private final List<Runnable> tasks = new ArrayList<>();
        private final List<RecordingFuture<?>> futures = new ArrayList<>();

        private RecordingScheduler() {
            super(1);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            RecordingFuture<Void> future = new RecordingFuture<>();
            tasks.add(command);
            futures.add(future);
            return future;
        }
    }

    private static final class RecordingFuture<V> implements ScheduledFuture<V> {
        private boolean cancelled;

        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(Delayed other) {
            return 0;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return cancelled;
        }

        @Override
        public V get() {
            throw new UnsupportedOperationException();
        }

        @Override
        public V get(long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }
    }
}
