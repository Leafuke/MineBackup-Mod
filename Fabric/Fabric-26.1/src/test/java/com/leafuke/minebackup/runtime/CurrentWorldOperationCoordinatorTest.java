package com.leafuke.minebackup.runtime;

import com.leafuke.minebackup.api.v1.BackupRequest;
import com.leafuke.minebackup.api.v1.BackupResult;
import com.leafuke.minebackup.api.v1.OperationFailure;
import com.leafuke.minebackup.api.v1.OperationPhase;
import com.leafuke.minebackup.api.v1.RestoreControlResult;
import com.leafuke.minebackup.api.v1.RestoreExecutionPolicy;
import com.leafuke.minebackup.api.v1.RestoreHandle;
import com.leafuke.minebackup.api.v1.RestoreRequest;
import com.leafuke.minebackup.api.v1.RestoreResult;
import com.leafuke.minebackup.knotlink.protocol.KnotLinkRequest;
import com.leafuke.minebackup.knotlink.protocol.KnotLinkResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        assertEquals(Optional.of("snapshot.7z"), result.fileName());
        assertEquals(OperationPhase.SUCCEEDED, handle.phase());
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
        assertTrue(result.fileName().isEmpty());
    }

    @Test
    void restoreCanBeCancelledBeforeSubmission() throws Exception {
        CurrentWorldOperationCoordinator coordinator = coordinator();
        RestoreHandle handle = coordinator.restoreCurrent(RestoreRequest.latest("test"));

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
        RestoreHandle handle = coordinator.restoreCurrent(
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
        RestoreHandle handle = coordinator.restoreCurrent(RestoreRequest.latest("test"));

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
        RestoreRequest request = new RestoreRequest(
                "test",
                Optional.empty(),
                RestoreExecutionPolicy.IMMEDIATE);

        RestoreHandle handle = coordinator.restoreCurrent(request);

        assertEquals(1, gateway.requests.size());
        assertEquals(OperationPhase.RUNNING, handle.phase());
        assertEquals(0, listener.started);
    }

    @Test
    void dedicatedServerRejectsHotRestore() throws Exception {
        dedicated.set(true);
        CurrentWorldOperationCoordinator coordinator = coordinator();

        RestoreHandle handle = coordinator.restoreCurrent(RestoreRequest.latest("test"));
        RestoreResult result = handle.completion().toCompletableFuture().get(1, TimeUnit.SECONDS);

        assertEquals(RestoreResult.Outcome.REJECTED, result.outcome());
        assertEquals(
                OperationFailure.Code.UNSUPPORTED_DEDICATED_SERVER,
                result.failure().orElseThrow().code());
        assertTrue(gateway.requests.isEmpty());
    }

    @Test
    void serverStopCancelsPendingRestoreButPreservesSubmittedIntegratedRestore() throws Exception {
        CurrentWorldOperationCoordinator coordinator = coordinator();
        RestoreHandle pending = coordinator.restoreCurrent(RestoreRequest.latest("pending"));

        coordinator.serverStopping(false);
        RestoreResult stopped = pending.completion().toCompletableFuture().get(1, TimeUnit.SECONDS);
        assertEquals(RestoreResult.Outcome.FAILED, stopped.outcome());
        assertEquals(
                OperationFailure.Code.SERVER_STOPPED,
                stopped.failure().orElseThrow().code());

        RestoreHandle submitted = coordinator.restoreCurrent(RestoreRequest.latest("submitted"));
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
    void automaticBackupIntervalMustUseWholeSupportedMinutes() {
        assertEquals(30, AutoBackupScheduler.validateInterval(Duration.ofMinutes(30)));
        assertThrows(
                IllegalArgumentException.class,
                () -> AutoBackupScheduler.validateInterval(Duration.ofSeconds(90)));
        assertThrows(
                IllegalArgumentException.class,
                () -> AutoBackupScheduler.validateInterval(Duration.ZERO));
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
        public void onStarted(RestoreHandle handle, int seconds) {
            started++;
        }

        @Override
        public void onTick(RestoreHandle handle, int seconds) {
        }

        @Override
        public void onConfirmed(RestoreHandle handle) {
        }

        @Override
        public void onCancelled(RestoreHandle handle) {
            cancelled++;
        }

        @Override
        public void onSubmitted(RestoreHandle handle) {
            submitted++;
        }
    }
}
