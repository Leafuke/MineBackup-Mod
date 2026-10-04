package com.leafuke.minebackup.runtime;

import com.leafuke.minebackup.api.v2.BackupRequest;
import com.leafuke.minebackup.api.v2.BackupResult;
import com.leafuke.minebackup.api.v2.BackupCatalogRequest;
import com.leafuke.minebackup.api.v2.BackupCatalogResult;
import com.leafuke.minebackup.api.v2.OperationFailure;
import com.leafuke.minebackup.api.v2.OperationPhase;
import com.leafuke.minebackup.api.v2.RestoreExecutionPolicy;
import com.leafuke.minebackup.api.v2.RestoreRequest;
import com.leafuke.minebackup.api.v2.RestoreResult;
import com.leafuke.minebackup.knotlink.protocol.KnotLinkRequest;
import com.leafuke.minebackup.knotlink.protocol.KnotLinkCodec;
import com.leafuke.minebackup.knotlink.protocol.KnotLinkResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrentWorldOperationCoordinatorTest {
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();
    private final FakeGateway gateway = new FakeGateway();
    private final AtomicBoolean serverAvailable = new AtomicBoolean(true);
    private final AtomicBoolean dedicated = new AtomicBoolean(false);
    private final AtomicInteger countdownSeconds = new AtomicInteger(10);
    private final RecordingCountdownListener listener = new RecordingCountdownListener();

    @Test void perRequestCountdownFreezesAndPreservesLegacyConstruction() {
        var base = RestoreRequest.file("test", "one.7z");
        var legacy = new RestoreRequest(base.callerId(), base.backupId(), base.comment(), base.executionPolicy(), base.parameters(), base.presentation());
        assertTrue(legacy.countdownSeconds().isEmpty());
        for (int seconds : new int[] {0, 1, 300}) {
            var request = base.withCountdownSeconds(seconds).withComment("test")
                .withParameter("verify_archive", "true").withPresentation(base.presentation());
            assertEquals(seconds, request.countdownSeconds().orElseThrow());
            assertTrue(request.immediate().countdownSeconds().isEmpty());
            assertEquals(seconds, request.immediate().withCountdownSeconds(seconds).countdownSeconds().orElseThrow());
        }
        assertThrows(IllegalArgumentException.class, () -> base.withCountdownSeconds(-1));
        assertThrows(IllegalArgumentException.class, () -> base.withCountdownSeconds(301));
        var coordinator = coordinator();
        var handle = coordinator.restoreCurrent(base.withCountdownSeconds(300));
        assertEquals(OperationPhase.COUNTING_DOWN, handle.phase());
        countdownSeconds.set(1);
        assertTrue(handle.remaining().toSeconds() > 290);
        handle.cancel();
        var zero = coordinator.restoreCurrent(base.withCountdownSeconds(0));
        assertFalse(zero.phase() == OperationPhase.COUNTING_DOWN);
        assertFalse(gateway.requests.getLast().serialize().contains("countdown"));
    }

    @AfterEach
    void closeScheduler() {
        scheduler.shutdownNow();
    }

    @Test
    void backupCompletesWithArchiveFromMatchingSignal() throws Exception {
        CurrentWorldOperationCoordinator coordinator = coordinator();
        var handle = coordinator.backupCurrent(BackupRequest.create("test", "danger"));

        assertEquals(OperationPhase.RUNNING, handle.phase());
        assertEquals(1, gateway.requests.size());
        assertTrue(gateway.requests.getFirst().serialize().contains("cmd=BACKUP"));

        coordinator.handleSignal(Map.of(
                "event", "backup_success",
                "request_id", handle.id().toString(),
                "file", "snapshot.7z"));

        BackupResult result = handle.completion().toCompletableFuture().get(1, TimeUnit.SECONDS);
        assertEquals(BackupResult.Outcome.CREATED, result.outcome());
        assertEquals("snapshot.7z", result.backupId().orElseThrow().value());
        assertEquals(OperationPhase.SUCCEEDED, handle.phase());
    }

    @Test
    void backupAndRestoreAdditionalParametersReachKnotLink() throws Exception {
        CurrentWorldOperationCoordinator coordinator = coordinator();
        var backup = coordinator.backupCurrent(
                BackupRequest.create("test")
                        .withParameter("backup_mode", "incremental")
                        .withParameter("compression_method", "zstd level=9"));

        Map<String, String> backupFields =
                KnotLinkCodec.parse(gateway.requests.getFirst().serialize());
        assertEquals("incremental", backupFields.get("backup_mode"));
        assertEquals("zstd level=9", backupFields.get("compression_method"));
        assertEquals("true", backupFields.get("current_save"));

        coordinator.handleSignal(Map.of(
                "event", "backup_success",
                "request_id", backup.id().toString(),
                "file", "snapshot.7z"));

        InternalRestoreHandle restore = coordinator.restoreCurrent(
                RestoreRequest.file("test", "snapshot.7z")
                        .withParameter("verify_archive", "true"));
        restore.confirm();

        Map<String, String> restoreFields =
                KnotLinkCodec.parse(gateway.requests.get(1).serialize());
        assertEquals("true", restoreFields.get("verify_archive"));
        assertEquals("snapshot.7z", restoreFields.get("file"));
        assertEquals("true", restoreFields.get("current_save"));
    }

    @Test
    void unrelatedRequestSignalIsIgnoredAndConcurrentRequestIsBusy() throws Exception {
        CurrentWorldOperationCoordinator coordinator = coordinator();
        var first = coordinator.backupCurrent(BackupRequest.create("first"));
        var second = coordinator.backupCurrent(BackupRequest.create("second"));

        BackupResult busy = second.completion().toCompletableFuture().get(1, TimeUnit.SECONDS);
        assertEquals(BackupResult.Outcome.REJECTED, busy.outcome());
        assertEquals(
                OperationFailure.Code.BUSY,
                busy.failure().orElseThrow().code());

        coordinator.handleSignal(Map.of(
                "event", "backup_success",
                "request_id", "00000000-0000-0000-0000-000000000000",
                "file", "wrong.7z"));
        assertFalse(first.completion().toCompletableFuture().isDone());
    }

    @Test
    void noChangesIsSuccessfulWithoutInventingArchiveName() throws Exception {
        CurrentWorldOperationCoordinator coordinator = coordinator();
        var handle = coordinator.backupCurrent(BackupRequest.create("test"));

        coordinator.handleSignal(Map.of(
                "event", "command_completed",
                "command", "BACKUP",
                "request_id", handle.id().toString(),
                "result", "no_changes"));

        BackupResult result = handle.completion().toCompletableFuture().get(1, TimeUnit.SECONDS);
        assertEquals(BackupResult.Outcome.NO_CHANGES, result.outcome());
        assertTrue(result.backupId().isEmpty());
    }

    @Test
    void restoreCanBeCancelledBeforeSubmission() throws Exception {
        CurrentWorldOperationCoordinator coordinator = coordinator();
        InternalRestoreHandle handle = coordinator.restoreCurrent(RestoreRequest.latest("test"));

        assertEquals(OperationPhase.COUNTING_DOWN, handle.phase());
        assertEquals(0, gateway.requests.size());
        assertEquals(RestoreControlResult.CANCELLED, handle.cancel());
        assertEquals(RestoreControlResult.NOT_PENDING, handle.cancel());
        assertEquals(0, gateway.requests.size());

        RestoreResult result = handle.completion().toCompletableFuture().get(1, TimeUnit.SECONDS);
        assertEquals(RestoreResult.Outcome.CANCELLED, result.outcome());
        assertEquals(1, listener.cancelled);
    }

    @Test
    void confirmSubmitsRestoreExactlyOnce() throws Exception {
        CurrentWorldOperationCoordinator coordinator = coordinator();
        InternalRestoreHandle handle = coordinator.restoreCurrent(
                RestoreRequest.file("test", "snapshot.7z"));

        assertEquals(RestoreControlResult.CONFIRMED, handle.confirm());
        assertEquals(RestoreControlResult.ALREADY_SUBMITTED, handle.confirm());
        assertEquals(1, gateway.requests.size());
        assertTrue(gateway.requests.getFirst().serialize().contains("cmd=RESTORE"));

        coordinator.completeClientRejoin(true, null);
        RestoreResult result = handle.completion().toCompletableFuture().get(1, TimeUnit.SECONDS);
        assertEquals(RestoreResult.Outcome.RESTORED, result.outcome());
    }

    @Test
    void configuredCountdownExpiresAndSubmits() throws Exception {
        countdownSeconds.set(1);
        CurrentWorldOperationCoordinator coordinator = coordinator();
        InternalRestoreHandle handle = coordinator.restoreCurrent(RestoreRequest.latest("test"));

        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (gateway.requests.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }

        assertEquals(1, gateway.requests.size());
        assertEquals(OperationPhase.RUNNING, handle.phase());
        assertEquals(1, listener.submitted);
    }

    @Test
    void immediatePolicyBypassesCountdown() {
        CurrentWorldOperationCoordinator coordinator = coordinator();
        RestoreRequest request = RestoreRequest.latest("test").immediate();

        InternalRestoreHandle handle = coordinator.restoreCurrent(request);

        assertEquals(1, gateway.requests.size());
        assertEquals(OperationPhase.RUNNING, handle.phase());
        assertEquals(0, listener.started);
    }

    @Test
    void dedicatedServerUsesSameCoordinatedRestoreSubmission() {
        dedicated.set(true);
        CurrentWorldOperationCoordinator coordinator = coordinator();

        InternalRestoreHandle handle = coordinator.restoreCurrent(RestoreRequest.latest("test"));

        assertEquals(OperationPhase.COUNTING_DOWN, handle.phase());
        assertTrue(gateway.requests.isEmpty());
        assertEquals(RestoreControlResult.CONFIRMED, handle.confirm());
        assertEquals(1, gateway.requests.size());
    }

    @Test
    void serverStopCancelsPendingRestoreButPreservesSubmittedIntegratedRestore() throws Exception {
        CurrentWorldOperationCoordinator coordinator = coordinator();
        InternalRestoreHandle pending = coordinator.restoreCurrent(RestoreRequest.latest("pending"));

        coordinator.serverStopping(false);
        RestoreResult stopped = pending.completion().toCompletableFuture().get(1, TimeUnit.SECONDS);
        assertEquals(RestoreResult.Outcome.FAILED, stopped.outcome());
        assertEquals(
                OperationFailure.Code.SERVER_STOPPED,
                stopped.failure().orElseThrow().code());

        InternalRestoreHandle submitted = coordinator.restoreCurrent(RestoreRequest.latest("submitted"));
        submitted.confirm();
        coordinator.serverStopping(true);
        assertFalse(submitted.completion().toCompletableFuture().isDone());

        coordinator.completeClientRejoin(true, null);
        assertEquals(
                RestoreResult.Outcome.RESTORED,
                submitted.completion().toCompletableFuture().get(1, TimeUnit.SECONDS).outcome());
    }

    @Test
    void saveTimeoutReleasesOperationGate() throws Exception {
        CurrentWorldOperationCoordinator coordinator = coordinator();
        var timedOut = coordinator.backupCurrent(BackupRequest.create("first"));

        coordinator.failActiveBackupTimeout();

        assertEquals(
                OperationFailure.Code.SAVE_TIMEOUT,
                timedOut.completion().toCompletableFuture()
                        .get(1, TimeUnit.SECONDS)
                        .failure()
                        .orElseThrow()
                        .code());
        var next = coordinator.backupCurrent(BackupRequest.create("second"));
        assertEquals(OperationPhase.RUNNING, next.phase());
    }

    @Test
    void catalogUsesCurrentWorldGate() throws Exception {
        CurrentWorldOperationCoordinator coordinator = coordinator();
        var backup = coordinator.backupCurrent(BackupRequest.create("first"));
        BackupCatalogResult busy = coordinator
                .listCurrentBackups(BackupCatalogRequest.create("time_machine:ui"))
                .toCompletableFuture()
                .get(1, TimeUnit.SECONDS);
        assertEquals(BackupCatalogResult.Outcome.BUSY, busy.outcome());

        coordinator.failActiveBackupTimeout();
        BackupCatalogResult success = coordinator
                .listCurrentBackups(BackupCatalogRequest.create("time_machine:ui"))
                .toCompletableFuture()
                .get(1, TimeUnit.SECONDS);
        assertEquals(BackupCatalogResult.Outcome.SUCCESS, success.outcome());
        assertTrue(success.entries().isEmpty());
    }

    @Test
    void remoteRestoreIsAdoptedAndDedicatedHandoffCompletesBeforeShutdown() throws Exception {
        CurrentWorldOperationCoordinator coordinator = coordinator();
        UUID requestId = UUID.randomUUID();

        assertTrue(coordinator.adoptRemoteRestore(requestId));
        assertEquals("folderrewind:remote", coordinator.activeSnapshot().orElseThrow().callerId());
        assertFalse(coordinator.adoptRemoteRestore(UUID.randomUUID()));

        InternalRestoreHandle restore = coordinator.activeRestore().orElseThrow();
        coordinator.completeDedicatedHandoff();
        RestoreResult result =
                restore.completion().toCompletableFuture().get(1, TimeUnit.SECONDS);
        assertEquals(RestoreResult.Outcome.RESTART_HANDOFF_ACCEPTED, result.outcome());
        coordinator.serverStopping(false);
        assertEquals(RestoreResult.Outcome.RESTART_HANDOFF_ACCEPTED, result.outcome());
    }

    @Test
    void automaticBackupIntervalMustUseWholeSupportedMinutes() {
        assertEquals(30, AutoBackupScheduler.validateInterval(Duration.ofMinutes(30)));
        assertThrows(
                IllegalArgumentException.class,
                () -> AutoBackupScheduler.validateInterval(Duration.ofSeconds(90)));
        assertThrows(
                IllegalArgumentException.class,
                () -> AutoBackupScheduler.validateInterval(Duration.ZERO));
    }

    @Test
    void uuidCancellationIsAtomicAndDoesNotRequireCallerOwnership() throws Exception {
        var coordinator = coordinator();
        var restore = coordinator.restoreCurrent(RestoreRequest.latest("owner"));
        assertEquals(com.leafuke.minebackup.api.v2.RestoreCancelResult.NOT_PENDING,
                coordinator.cancelRestore(com.leafuke.minebackup.api.v2.RestoreCancelRequest.create("other", UUID.randomUUID())));
        assertEquals(com.leafuke.minebackup.api.v2.RestoreCancelResult.CANCELLED,
                coordinator.cancelRestore(com.leafuke.minebackup.api.v2.RestoreCancelRequest.create("other", restore.id())));
        assertEquals(RestoreResult.Outcome.CANCELLED, restore.completion().toCompletableFuture().get().outcome());
        assertEquals(com.leafuke.minebackup.api.v2.RestoreCancelResult.NOT_PENDING,
                coordinator.cancelRestore(com.leafuke.minebackup.api.v2.RestoreCancelRequest.create("other", restore.id())));
        assertTrue(gateway.requests.isEmpty());
        var backup = coordinator.backupCurrent(BackupRequest.create("backup"));
        assertEquals(com.leafuke.minebackup.api.v2.RestoreCancelResult.NOT_PENDING,
                coordinator.cancelRestore(com.leafuke.minebackup.api.v2.RestoreCancelRequest.create("other", backup.id())));
        coordinator.close();
    }

    @Test
    void immediateOrSubmittedRestoreCannotBeCancelled() {
        for (boolean immediate : new boolean[] {true, false}) {
            var coordinator = coordinator();
            var restore = coordinator.restoreCurrent(immediate ? RestoreRequest.latest("owner").immediate() : RestoreRequest.latest("owner"));
            if (!immediate) restore.confirm();
            assertEquals(com.leafuke.minebackup.api.v2.RestoreCancelResult.ALREADY_SUBMITTED,
                    coordinator.cancelRestore(com.leafuke.minebackup.api.v2.RestoreCancelRequest.create("other", restore.id())));
            coordinator.close();
        }
    }

    @Test
    void cancellationCompletionCanImmediatelyStartAnotherOperation() {
        var coordinator = coordinator();
        var restore = coordinator.restoreCurrent(RestoreRequest.latest("owner"));
        var next = new java.util.concurrent.atomic.AtomicReference<com.leafuke.minebackup.api.v2.OperationHandle<BackupResult>>();
        restore.completion().thenRun(() -> next.set(coordinator.backupCurrent(BackupRequest.create("callback"))));
        coordinator.cancelRestore(com.leafuke.minebackup.api.v2.RestoreCancelRequest.create("other", restore.id()));
        assertEquals(next.get().id(), coordinator.activeSnapshot().orElseThrow().requestId());
        assertEquals(OperationPhase.RUNNING, next.get().phase());
        coordinator.close();
    }

    @Test
    void cancellationRacingExpiryHasExactlyOneWinnerAndConfirmationCannotDoubleSubmit() throws Exception {
        var workers = Executors.newFixedThreadPool(2);
        try (var clockScheduler = new CapturingScheduler()) {
            for (int i = 0; i < 60; i++) {
                var clock = new java.util.concurrent.atomic.AtomicLong(0);
                var events = new RecordingCountdownListener();
                var localGateway = new FakeGateway();
                var coordinator = new CurrentWorldOperationCoordinator(localGateway, clockScheduler,
                        () -> true, () -> false, () -> 10, events, clock::get);
                var restore = coordinator.restoreCurrent(RestoreRequest.latest("owner"));
                Runnable expiry = clockScheduler.tick;
                clock.set(Duration.ofSeconds(11).toNanos());
                var start = new java.util.concurrent.CountDownLatch(1);
                boolean confirm = i % 2 == 0;
                var first = workers.submit(() -> { start.await();
                    if (confirm) restore.confirm();
                    else coordinator.cancelRestore(com.leafuke.minebackup.api.v2.RestoreCancelRequest.create("other", restore.id()));
                    return null;
                });
                var second = workers.submit(() -> { start.await(); expiry.run(); return null; });
                start.countDown(); first.get(2, TimeUnit.SECONDS); second.get(2, TimeUnit.SECONDS);
                assertEquals(1, events.cancelled + events.submitted);
                assertEquals(events.submitted, localGateway.requests.size());
                coordinator.close();
            }
        } finally { workers.shutdownNow(); }
    }

    @Test
    void backendQueriesDoNotOccupyOrReplaceAnActiveBackup() throws Exception {
        var coordinator = coordinator();
        var backup = coordinator.backupCurrent(BackupRequest.create("active"));
        var queries = new BackendQueries(request -> CompletableFuture.completedFuture(new KnotLinkResponse(
                KnotLinkResponse.Status.OK, null, "enabled=true", Map.of("status", "ok", "func_list",
                "{\"specVersion\":\"1.0\",\"manifestVersion\":\"test\",\"openSocket\":{}}"))),
                () -> this, () -> com.leafuke.minebackup.api.v2.BackendStatusResult.ChannelState.CONNECTED);
        assertEquals(com.leafuke.minebackup.api.v2.BackendCapabilitiesResult.Outcome.SUCCESS,
                queries.capabilities().toCompletableFuture().get().outcome());
        assertEquals(com.leafuke.minebackup.api.v2.BackendStatusResult.Outcome.SUCCESS,
                queries.status().toCompletableFuture().get().outcome());
        assertEquals(backup.id(), coordinator.activeSnapshot().orElseThrow().requestId());
        assertFalse(backup.completion().toCompletableFuture().isDone());
        coordinator.close();
    }

    private static final class CapturingScheduler extends java.util.concurrent.ScheduledThreadPoolExecutor {
        private Runnable tick;
        CapturingScheduler() { super(1); }
        @Override public java.util.concurrent.ScheduledFuture<?> scheduleAtFixedRate(
                Runnable command, long delay, long period, TimeUnit unit) {
            tick = command;
            return super.scheduleAtFixedRate(command, 1, 1, TimeUnit.DAYS);
        }
    }

    private CurrentWorldOperationCoordinator coordinator() {
        return new CurrentWorldOperationCoordinator(
                gateway,
                scheduler,
                serverAvailable::get,
                dedicated::get,
                countdownSeconds::get,
                listener);
    }

    private static final class FakeGateway implements KnotLinkGateway {
        private final List<KnotLinkRequest> requests = new CopyOnWriteArrayList<>();

        @Override
        public synchronized CompletableFuture<KnotLinkResponse> query(KnotLinkRequest request) {
            requests.add(request);
            return CompletableFuture.completedFuture(new KnotLinkResponse(
                    KnotLinkResponse.Status.OK,
                    "accepted",
                    null,
                    Map.of("status", "ok")));
        }
    }

    private static final class RecordingCountdownListener
            implements CurrentWorldOperationCoordinator.CountdownListener {
        private int started;
        private int cancelled;
        private int submitted;

        @Override
        public void onStarted(InternalRestoreHandle handle, int seconds) {
            started++;
        }

        @Override
        public void onTick(InternalRestoreHandle handle, int seconds) {
        }

        @Override
        public void onConfirmed(InternalRestoreHandle handle) {
        }

        @Override
        public void onCancelled(InternalRestoreHandle handle) {
            cancelled++;
        }

        @Override
        public void onSubmitted(InternalRestoreHandle handle) {
            submitted++;
        }
    }
}
