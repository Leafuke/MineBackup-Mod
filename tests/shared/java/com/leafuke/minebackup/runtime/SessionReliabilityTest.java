package com.leafuke.minebackup.runtime;

import com.leafuke.minebackup.api.v2.*;
import com.leafuke.minebackup.restore.RestoreSession;
import com.leafuke.minebackup.knotlink.protocol.KnotLinkResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SessionReliabilityTest {
    @org.junit.jupiter.api.io.TempDir Path temporary;
    private final ManualScheduler scheduler = new ManualScheduler();
    private final AtomicBoolean connected = new AtomicBoolean(true);
    private final AtomicBoolean frozen = new AtomicBoolean();
    private final AtomicInteger submissions = new AtomicInteger();
    private final AtomicBoolean throwSubmission = new AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicReference<CompletableFuture<KnotLinkResponse>> nextReply = new java.util.concurrent.atomic.AtomicReference<>();
    private final CurrentWorldOperationCoordinator operations = new CurrentWorldOperationCoordinator(request -> {
        submissions.incrementAndGet();
        if (throwSubmission.get()) throw new IllegalStateException("gateway failed synchronously");
        var reply = nextReply.getAndSet(null);
        if (reply != null) return reply;
        return CompletableFuture.completedFuture(new KnotLinkResponse(KnotLinkResponse.Status.OK, "ok", "", Map.of()));
    }, scheduler, () -> true, () -> false, () -> 10, new CurrentWorldOperationCoordinator.CountdownListener() {
        public void onStarted(InternalRestoreHandle h, int seconds) {}
        public void onTick(InternalRestoreHandle h, int seconds) {}
        public void onConfirmed(InternalRestoreHandle h) {}
        public void onCancelled(InternalRestoreHandle h) {}
        public void onSubmitted(InternalRestoreHandle h) {}
    }, scheduler::now);

    SessionReliabilityTest() {
        operations.worldStarted(Path.of("world"));
        operations.bindLifecycle(connected::get, (token, presentation, failure, complete) -> {
            frozen.set(false); complete.run();
        });
    }
    @AfterEach void close() { operations.close(); scheduler.shutdownNow(); }
    private WorldOperationSession.Token prepare(String action) {
        var token = operations.handshake(Map.of("action", action, "world", "world"));
        assertNotNull(token);
        assertEquals(token, operations.consumeHandshake(RestoreSession.Action.valueOf(action.toUpperCase()),
                Map.of("world", "world", "config", "config", "folder_id", "folder")));
        if (action.equals("backup")) { assertTrue(operations.markFrozen(token)); frozen.set(true); }
        return token;
    }

    @Test void foreignAndUnidentifiedTerminalsCannotUnfreezeOrCompleteLocalBackup() {
        var handle = operations.backupCurrent(BackupRequest.create("test"));
        prepare("backup");
        assertFalse(operations.handleSignal(Map.of("event", "backup_success", "request_id", UUID.randomUUID().toString(), "file", "wrong.7z")));
        assertFalse(operations.handleSignal(Map.of("event", "backup_success", "world", "other", "file", "wrong.7z")));
        assertFalse(operations.handleSignal(Map.of("event", "backup_success", "world", "world", "file", "wrong.7z")));
        assertFalse(operations.handleSignal(Map.of("event", "backup_success", "world", "other", "request_id", handle.id().toString(), "file", "wrong.7z")));
        assertTrue(frozen.get()); assertFalse(handle.completion().toCompletableFuture().isDone());
        assertTrue(operations.handleSignal(Map.of("event", "backup_success", "request_id", handle.id().toString(), "file", "right.7z")));
        assertFalse(frozen.get());
        assertEquals("right.7z", handle.completion().toCompletableFuture().join().backupId().orElseThrow().value());
    }

    @Test void legacyGuiBackupRequiresMatchingHandshakeAndTarget() {
        assertNull(operations.handshake(Map.of("action", "backup", "world", "other")));
        prepare("backup");
        assertFalse(operations.handleSignal(Map.of("event", "backup_success", "file", "wrong.7z")));
        assertTrue(operations.handleSignal(Map.of("event", "backup_success", "world", "world", "file", "right.7z")));
        assertFalse(frozen.get()); assertFalse(operations.isBusy());
    }

    @Test void minebackupNumericAndStableConfigFieldsReferToSameBoundTarget() {
        var handle = operations.backupCurrent(BackupRequest.create("test"));
        var token = operations.handshake(Map.of("action", "backup", "world", "world"));
        assertEquals(token, operations.consumeHandshake(RestoreSession.Action.BACKUP,
                Map.of("world", "world", "folder", "world", "config", "0", "config_id", "stable-config")));
        operations.markFrozen(token); frozen.set(true);
        assertFalse(operations.handleSignal(Map.of("event", "backup_success", "request_id", handle.id().toString(),
                "config", "different-config", "folder", "world", "file", "wrong.7z")));
        assertTrue(operations.handleSignal(Map.of("event", "backup_success", "request_id", handle.id().toString(),
                "config", "stable-config", "folder", "world", "file", "right.7z")));
        assertFalse(frozen.get());
    }

    @Test void expiredHandshakeCannotFreezeAndReleaseDeadlineCannotOpenWorld() {
        var handle = operations.backupCurrent(BackupRequest.create("test"));
        var token = operations.handshake(Map.of("action", "backup", "world", "world"));
        scheduler.advance(Duration.ofSeconds(5));
        assertTrue(handle.phase().isTerminal());
        assertNull(operations.consumeHandshake(RestoreSession.Action.BACKUP, Map.of("world", "world")));
        assertFalse(operations.markFrozen(token));
        var restore = operations.restoreCurrent(RestoreRequest.latest("test").immediate());
        token = prepare("restore");
        operations.beginWorldRelease(token, new RestoreSession.RejoinInfo("world", false, -1));
        scheduler.advance(Duration.ofSeconds(8));
        assertTrue(restore.phase().isTerminal());
        assertFalse(operations.markWorldReleased(token));
        assertNull(operations.beginRejoin(Map.of("world", "world")));
    }

    @Test void backupTimeoutResumesSavingCompletesOnceAndAllowsNormalNextOperation() {
        var handle = operations.backupCurrent(BackupRequest.create("test"));
        prepare("backup"); connected.set(false);
        scheduler.advance(Duration.ofMinutes(10));
        assertFalse(frozen.get()); assertFalse(operations.isBusy());
        assertEquals(OperationFailure.Code.SAVE_TIMEOUT, handle.completion().toCompletableFuture().join().failure().orElseThrow().code());
        assertFalse(operations.handleSignal(Map.of("event", "backup_success", "request_id", handle.id().toString(), "file", "late.7z")));
        assertEquals(1, submissions.get()); // No retry.
        connected.set(true);
        assertEquals(OperationPhase.RUNNING, operations.backupCurrent(BackupRequest.create("next")).phase());
    }

    @Test void missingHandshakeExpiresAndDisconnectedSubmissionDoesNotSend() {
        connected.set(false);
        assertTrue(operations.backupCurrent(BackupRequest.create("offline")).phase().isTerminal());
        assertEquals(0, submissions.get());
        connected.set(true);
        var handle = operations.backupCurrent(BackupRequest.create("test"));
        scheduler.advance(Duration.ofSeconds(30));
        assertTrue(handle.phase().isTerminal()); assertFalse(operations.isBusy());
    }

    @Test void restoreFailureClearsSessionAndStaleCallbacksCannotFinishNextRequest() {
        var handle = operations.restoreCurrent(RestoreRequest.latest("test").immediate());
        var token = prepare("restore");
        assertTrue(operations.beginWorldRelease(token, new RestoreSession.RejoinInfo("world", false, -1)));
        assertTrue(operations.markWorldReleased(token));
        assertTrue(operations.handleSignal(Map.of("event", "command_failed", "command", "RESTORE", "request_id", handle.id().toString())));
        assertNull(operations.sessionToken()); assertFalse(operations.preserveRestoreOnStop());
        var next = operations.backupCurrent(BackupRequest.create("next"));
        operations.failSession(token, OperationFailure.Code.RESTORE_FAILED, "late failure");
        operations.completeClientRejoin(token, true, null);
        assertEquals(OperationPhase.RUNNING, next.phase());
    }

    @Test void restoreSafetyBackupReleasesOnlySubstageAndLegacyRejoinIsOrdered() {
        var handle = operations.restoreCurrent(RestoreRequest.latest("test").immediate());
        var save = prepare("backup");
        assertTrue(operations.handleSignal(Map.of("event", "backup_success", "request_id", handle.id().toString(), "file", "safety.7z")));
        assertFalse(frozen.get()); assertEquals(OperationPhase.RUNNING, handle.phase());
        assertFalse(operations.isCurrent(save));
        var restore = prepare("restore");
        assertNull(operations.beginRejoin(Map.of("world", "world")));
        assertFalse(operations.restoreFinished(Map.of("world", "world", "status", "success")));
        assertTrue(operations.beginWorldRelease(restore, new RestoreSession.RejoinInfo("world", true, 25565)));
        assertTrue(operations.markWorldReleased(restore));
        assertTrue(operations.restoreFinished(Map.of("world", "world", "status", "success")));
        assertFalse(operations.restoreFinished(Map.of("world", "world", "status", "success")));
        assertNotNull(operations.beginRejoin(Map.of("world", "world")));
        operations.worldStarted(Path.of("world"));
        assertTrue(operations.isCurrent(restore), "Expected reopen must preserve its originating restore");
        operations.completeClientRejoin(restore, true, null);
        assertEquals(RestoreResult.Outcome.RESTORED, handle.completion().toCompletableFuture().join().outcome());
    }

    @Test void restoreAndRejoinDeadlinesEndWithoutOpeningWorld() {
        var handle = operations.restoreCurrent(RestoreRequest.latest("test").immediate());
        var token = prepare("restore");
        operations.beginWorldRelease(token, new RestoreSession.RejoinInfo("world", false, -1));
        operations.markWorldReleased(token);
        scheduler.advance(Duration.ofHours(1));
        assertEquals(RestoreResult.Outcome.FAILED, handle.completion().toCompletableFuture().join().outcome());
        assertNull(operations.beginRejoin(Map.of("world", "world")));
        var next = operations.restoreCurrent(RestoreRequest.latest("test").immediate());
        token = prepare("restore");
        operations.beginWorldRelease(token, new RestoreSession.RejoinInfo("world", false, -1));
        operations.markWorldReleased(token);
        operations.restoreFinished(Map.of("world", "world", "status", "success"));
        scheduler.advance(Duration.ofSeconds(30));
        assertTrue(next.phase().isTerminal()); assertNull(operations.sessionToken());
        assertEquals(RestoreResult.Outcome.RESTORED_REJOIN_FAILED, next.completion().toCompletableFuture().join().outcome());
    }

    @Test void configuredDedicatedStartupIsNotCutShortByGenericPreparationDeadline() {
        var handle = operations.restoreCurrent(RestoreRequest.latest("test").immediate());
        var token = prepare("restore");
        assertTrue(operations.prepareDedicated(token, 60));
        scheduler.advance(Duration.ofSeconds(31));
        assertFalse(handle.phase().isTerminal());
        operations.completeDedicatedHandoff();
        assertEquals(RestoreResult.Outcome.RESTART_HANDOFF_ACCEPTED, handle.completion().toCompletableFuture().join().outcome());
        scheduler.advance(Duration.ofMinutes(2));
        assertNull(operations.sessionToken());
    }

    @Test void completionCallbacksSeeReleasedFreezeAndCanSubmitImmediately() {
        var handle = operations.backupCurrent(BackupRequest.create("test")); prepare("backup");
        var next = handle.completion().thenApply(result -> {
            assertFalse(frozen.get()); assertFalse(operations.isBusy());
            return operations.backupCurrent(BackupRequest.create("next"));
        });
        operations.handleSignal(Map.of("event", "backup_success", "request_id", handle.id().toString(), "file", "ok.7z"));
        assertEquals(OperationPhase.RUNNING, next.toCompletableFuture().join().phase());
    }

    @Test void worldReplacementInvalidatesTokensAndUnknownWireIdsRemainOpaque() {
        var handle = operations.backupCurrent(BackupRequest.create("test"));
        var old = prepare("backup");
        operations.worldStarted(Path.of("other"));
        assertTrue(handle.phase().isTerminal()); assertFalse(operations.isCurrent(old));
        var token = operations.handshake(Map.of("action", "restore", "world", "other", "request_id", "gui:operation/17", "from", "desktop"));
        assertNotNull(token); assertEquals("gui:operation/17", operations.wireRequestId());
        assertNull(operations.consumeHandshake(RestoreSession.Action.RESTORE, Map.of("world", "other", "request_id", "wrong")));
        assertNotNull(operations.consumeHandshake(RestoreSession.Action.RESTORE, Map.of("world", "other", "request_id", "gui:operation/17")));
    }

    @Test void completedRequestCannotBeReadoptedByLateHandshake() {
        assertNull(operations.handshake(Map.of("action", "backup", "world", "world",
                "request_id", UUID.randomUUID().toString(), "from", "minebackup.mod")));
        var handle = operations.backupCurrent(BackupRequest.create("test"));
        operations.handleSignal(Map.of("event", "backup_success", "request_id", handle.id().toString(), "file", "ok.7z"));
        assertNull(operations.handshake(Map.of("action", "backup", "world", "world", "request_id", handle.id().toString())));
        assertFalse(operations.isBusy());
    }

    @Test void synchronousSubmissionErrorsAndRejectedSchedulerReleaseTheGate() {
        throwSubmission.set(true);
        assertTrue(operations.backupCurrent(BackupRequest.create("test")).phase().isTerminal());
        assertFalse(operations.isBusy());
        throwSubmission.set(false);
        scheduler.shutdown();
        assertTrue(operations.backupCurrent(BackupRequest.create("test")).phase().isTerminal());
        assertFalse(operations.isBusy());
        assertTrue(operations.restoreCurrent(RestoreRequest.latest("test")).phase().isTerminal());
        assertFalse(operations.isBusy());
        assertEquals(1, submissions.get());
    }

    @Test void uncertainSubmissionKeepsStageDeadlineAndCanStillReceiveMatchingSuccess() {
        nextReply.set(CompletableFuture.failedFuture(new com.leafuke.minebackup.knotlink.KnotLinkCommunicationException(
                OperationFailure.Code.RESPONSE_TIMEOUT, "reply lost", null)));
        var handle = operations.backupCurrent(BackupRequest.create("test"));
        prepare("backup");
        scheduler.advance(Duration.ofMinutes(1));
        assertTrue(frozen.get()); assertFalse(handle.phase().isTerminal());
        assertTrue(operations.handleSignal(Map.of("event", "backup_success", "request_id", handle.id().toString(), "file", "ok.7z")));
        assertEquals(BackupResult.Outcome.CREATED, handle.completion().toCompletableFuture().join().outcome());
    }

    @Test void protectedBackupRequiresFinalReceiptAndMetadataRejectionIsAlreadyCompleted() {
        operations.capabilitySource(() -> CompletableFuture.completedFuture(new BackendCapabilitiesResult(
                BackendCapabilitiesResult.Outcome.SUCCESS, "1.0", Map.of("BACKUP", new BackendCapabilitiesResult.Command(
                Map.of("protect", new BackendCapabilitiesResult.Parameter("input", java.util.Optional.empty(), java.util.List.of())))), "", 1)));
        var handle = operations.startBackup(BackupRequest.create("test"), true);
        prepare("backup");
        var busy = operations.protection(BackupProtectionRequest.query("test", BackupId.of("a.7z")), false);
        assertEquals(BackupProtectionResult.Outcome.REJECTED, busy.completion().toCompletableFuture().join().outcome());
        assertFalse(operations.handleSignal(Map.of("event", "backup_success", "request_id", handle.id().toString(), "file", "early.7z")));
        assertTrue(frozen.get()); assertFalse(handle.phase().isTerminal());
        assertTrue(operations.handleSignal(Map.of("event", "command_completed", "command", "BACKUP",
                "request_id", handle.id().toString(), "from", "minebackup.mod", "file", "final.7z", "result", "created", "important", "true")));
        assertFalse(frozen.get());
        assertTrue(handle.protectedView().completion().toCompletableFuture().join().important());
    }

    @Test void automaticBackupWaitsForNormalIntervalAfterTimeoutWithoutImmediateRetry() {
        var automation = new AutoBackupScheduler(operations, scheduler,
                java.time.Clock.fixed(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC),
                new com.leafuke.minebackup.config.WorldAutomationConfigStore(temporary), temporary, () -> {});
        try {
            automation.activateForTest(new com.leafuke.minebackup.config.WorldIdentity("relative:world", "world", "test"),
                    com.leafuke.minebackup.config.WorldAutomationConfigStore.Settings.backup(1));
            scheduler.advance(Duration.ofMinutes(1));
            assertEquals(1, submissions.get());
            scheduler.advance(Duration.ofSeconds(30));
            assertFalse(operations.isBusy()); assertEquals(1, submissions.get());
            scheduler.advance(Duration.ofSeconds(59));
            assertEquals(1, submissions.get());
            scheduler.advance(Duration.ofSeconds(1));
            assertEquals(2, submissions.get());
        } finally { automation.close(); }
    }
}
