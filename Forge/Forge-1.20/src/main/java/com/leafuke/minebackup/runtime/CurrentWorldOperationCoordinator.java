package com.leafuke.minebackup.runtime;

import com.leafuke.minebackup.api.v2.BackupId;
import com.leafuke.minebackup.api.v2.BackupProtectionRequest;
import com.leafuke.minebackup.api.v2.BackupProtectionResult;
import com.leafuke.minebackup.api.v2.BackupCatalogRequest;
import com.leafuke.minebackup.api.v2.BackupCatalogResult;
import com.leafuke.minebackup.api.v2.BackupRequest;
import com.leafuke.minebackup.api.v2.BackupResult;
import com.leafuke.minebackup.api.v2.OperationFailure;
import com.leafuke.minebackup.api.v2.OperationHandle;
import com.leafuke.minebackup.api.v2.OperationPhase;
import com.leafuke.minebackup.api.v2.OperationSnapshot;
import com.leafuke.minebackup.api.v2.OperationType;
import com.leafuke.minebackup.api.v2.OperationPresentation;
import com.leafuke.minebackup.api.v2.RestoreExecutionPolicy;
import com.leafuke.minebackup.api.v2.RestoreRequest;
import com.leafuke.minebackup.api.v2.RestoreResult;
import com.leafuke.minebackup.knotlink.protocol.KnotLinkRequest;
import com.leafuke.minebackup.knotlink.KnotLinkCommunicationException;
import com.leafuke.minebackup.api.v2.RestoreCancelRequest;
import com.leafuke.minebackup.api.v2.RestoreCancelResult;
import com.leafuke.minebackup.knotlink.protocol.KnotLinkResponse;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

final class CurrentWorldOperationCoordinator implements AutoCloseable {
    interface CountdownListener {
        void onStarted(InternalRestoreHandle handle, int seconds);

        void onTick(InternalRestoreHandle handle, int seconds);

        void onConfirmed(InternalRestoreHandle handle);

        void onCancelled(InternalRestoreHandle handle);

        void onSubmitted(InternalRestoreHandle handle);
    }

    private final KnotLinkGateway knotLink;
    private final ScheduledExecutorService scheduler;
    private final BooleanSupplier serverAvailable;
    private final BooleanSupplier dedicatedServer;
    private final IntSupplier configuredCountdownSeconds;
    private final CountdownListener countdownListener;
    private final LongSupplier nanoTime;

    private java.util.function.Supplier<CompletionStage<com.leafuke.minebackup.api.v2.BackendCapabilitiesResult>> capabilities;

    void capabilitySource(java.util.function.Supplier<CompletionStage<com.leafuke.minebackup.api.v2.BackendCapabilitiesResult>> source) {
        capabilities = Objects.requireNonNull(source);
    }

    private AbstractOperationHandle<?> active;
    private ScheduledFuture<?> countdownFuture;
    private long restoreDeadlineNanos;
    private int lastAnnouncedSecond = -1;
    private boolean closed;

    CurrentWorldOperationCoordinator(
            KnotLinkGateway knotLink,
            ScheduledExecutorService scheduler,
            BooleanSupplier serverAvailable,
            BooleanSupplier dedicatedServer,
            IntSupplier configuredCountdownSeconds,
            CountdownListener countdownListener) {
        this(
                knotLink,
                scheduler,
                serverAvailable,
                dedicatedServer,
                configuredCountdownSeconds,
                countdownListener,
                System::nanoTime);
    }

    CurrentWorldOperationCoordinator(
            KnotLinkGateway knotLink,
            ScheduledExecutorService scheduler,
            BooleanSupplier serverAvailable,
            BooleanSupplier dedicatedServer,
            IntSupplier configuredCountdownSeconds,
            CountdownListener countdownListener,
            LongSupplier nanoTime) {
        this.knotLink = Objects.requireNonNull(knotLink, "knotLink");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.serverAvailable = Objects.requireNonNull(serverAvailable, "serverAvailable");
        this.dedicatedServer = Objects.requireNonNull(dedicatedServer, "dedicatedServer");
        this.configuredCountdownSeconds =
                Objects.requireNonNull(configuredCountdownSeconds, "configuredCountdownSeconds");
        this.countdownListener = Objects.requireNonNull(countdownListener, "countdownListener");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        var discovery = new BackendQueries(knotLink, () -> serverAvailable.getAsBoolean() ? this : null,
                () -> com.leafuke.minebackup.api.v2.BackendStatusResult.ChannelState.UNKNOWN);
        capabilities = discovery::capabilities;
    }

    OperationHandle<BackupResult> backupCurrent(BackupRequest request) {
        return startBackup(request, false);
    }

    BackupOperationHandle startBackup(BackupRequest request, boolean protect) {
        Objects.requireNonNull(request, "request");
        if (request.parameters().containsKey("protect")) throw new IllegalArgumentException("Use backupProtectedCurrent for protection");
        BackupOperationHandle handle;
        synchronized (this) {
            if (closed || !serverAvailable.getAsBoolean()) {
                return rejectedBackup(
                        request,
                        OperationFailure.Code.NO_ACTIVE_SERVER,
                        "No active Minecraft server");
            }
            if (hasActiveOperation()) {
                return rejectedBackup(
                        request,
                        OperationFailure.Code.BUSY,
                        "Another current-world operation is active");
            }
            handle = new BackupOperationHandle(
                    UUID.randomUUID(),
                    request,
                    OperationPhase.SUBMITTING);
            handle.protectedBackup = protect;
            active = handle;
        }

        KnotLinkRequest command = KnotLinkRequest.command("BACKUP")
                .conversation(handle.id())
                .field("current_save", true);
        request.comment().ifPresent(comment -> command.field("comment", comment));
        request.parameters().forEach(command::field);
        if (protect) {
            command.field("protect", true);
            discoverProtection(handle, "BACKUP", true, () -> submitBackup(handle, command));
        } else submitBackup(handle, command);
        return handle;
    }

    InternalRestoreHandle restoreCurrent(RestoreRequest request) {
        Objects.requireNonNull(request, "request");
        RestoreOperationHandle handle;
        int seconds;
        synchronized (this) {
            if (closed || !serverAvailable.getAsBoolean()) {
                return rejectedRestore(
                        request,
                        OperationFailure.Code.NO_ACTIVE_SERVER,
                        "No active Minecraft server");
            }
            if (hasActiveOperation()) {
                return rejectedRestore(
                        request,
                        OperationFailure.Code.BUSY,
                        "Another current-world operation is active");
            }

            seconds = request.executionPolicy() == RestoreExecutionPolicy.IMMEDIATE
                    ? 0
                    : request.countdownSeconds().orElseGet(() -> Math.max(0, Math.min(300, configuredCountdownSeconds.getAsInt())));
            OperationPhase initial = seconds == 0
                    ? OperationPhase.SUBMITTING
                    : OperationPhase.COUNTING_DOWN;
            handle = new RestoreOperationHandle(UUID.randomUUID(), request, initial);
            handle.bindControls(
                    () -> remaining(handle),
                    () -> confirm(handle),
                    () -> cancel(handle));
            active = handle;

            if (seconds > 0) {
                restoreDeadlineNanos = nanoTime.getAsLong() + Duration.ofSeconds(seconds).toNanos();
                lastAnnouncedSecond = seconds;
                countdownFuture = scheduler.scheduleAtFixedRate(
                        () -> tickCountdown(handle),
                        1L,
                        1L,
                        TimeUnit.SECONDS);
            }
        }

        if (seconds > 0) {
            countdownListener.onStarted(handle, seconds);
        } else {
            submitRestore(handle);
        }
        return handle;
    }

    synchronized Optional<InternalRestoreHandle> pendingRestore() {
        if (active instanceof RestoreOperationHandle restore
                && restore.phase() == OperationPhase.COUNTING_DOWN) {
            return Optional.of(restore);
        }
        return Optional.empty();
    }

    synchronized Optional<OperationSnapshot> activeSnapshot() {
        if (active == null || active.phase().isTerminal()) {
            return Optional.empty();
        }
        OperationType type = active instanceof RestoreOperationHandle
                ? OperationType.RESTORE
                : active instanceof BackupProtectionHandle ? OperationType.BACKUP_PROTECTION
                : active instanceof CatalogOperationHandle
                        ? OperationType.CATALOG
                        : OperationType.BACKUP;
        return Optional.of(new OperationSnapshot(
                active.id(), active.callerId(), type, active.phase()));
    }

    synchronized boolean isBusy() {
        return hasActiveOperation();
    }

    CompletionStage<BackupCatalogResult> listCurrentBackups(BackupCatalogRequest request) {
        Objects.requireNonNull(request, "request");
        CatalogOperationHandle handle;
        synchronized (this) {
            if (closed || !serverAvailable.getAsBoolean()) {
                return java.util.concurrent.CompletableFuture.completedFuture(
                        BackupCatalogResult.failed(
                                BackupCatalogResult.Outcome.REJECTED,
                                new OperationFailure(
                                        OperationFailure.Code.NO_ACTIVE_SERVER,
                                        "No active Minecraft server")));
            }
            if (hasActiveOperation()) {
                return java.util.concurrent.CompletableFuture.completedFuture(
                        BackupCatalogResult.failed(
                                BackupCatalogResult.Outcome.BUSY,
                                new OperationFailure(
                                        OperationFailure.Code.BUSY,
                                        "Another current-world operation is active")));
            }
            handle = new CatalogOperationHandle(
                    UUID.randomUUID(), request.callerId(), OperationPhase.SUBMITTING);
            active = handle;
        }
        knotLink.query(KnotLinkRequest.command("LIST_BACKUPS")
                        .conversation(handle.id())
                        .field("current_save", true))
                .whenComplete((response, error) -> {
                    BackupCatalogResult result;
                    if (error != null) {
                        result = BackupCatalogResult.failed(
                                BackupCatalogResult.Outcome.FAILED,
                                KnotLinkCommunicationException.failure(error));
                    } else if (!response.isOk()) {
                        result = BackupCatalogResult.failed(
                                BackupCatalogResult.Outcome.FAILED,
                                new OperationFailure(
                                        OperationFailure.Code.BACKEND_REJECTED,
                                        response.displayMessage()));
                    } else {
                        try {
                            result = BackupCatalogResult.success(
                                    BackupCatalogParser.parseLegacy(response.data()));
                        } catch (IllegalArgumentException exception) {
                            result = BackupCatalogResult.failed(
                                    BackupCatalogResult.Outcome.FAILED,
                                    new OperationFailure(
                                            OperationFailure.Code.PROTOCOL_ERROR,
                                            exception.getMessage()));
                        }
                    }
                    OperationPhase phase = result.outcome() == BackupCatalogResult.Outcome.SUCCESS
                            ? OperationPhase.SUCCEEDED
                            : OperationPhase.FAILED;
                    if (handle.finish(phase, result)) {
                        release(handle);
                    }
                });
        return handle.completion();
    }

    InternalRestoreHandle rejectRestoreRequest(
            RestoreRequest request,
            OperationFailure.Code code,
            String message) {
        return rejectedRestore(request, code, message);
    }

    synchronized Optional<InternalRestoreHandle> activeRestore() {
        return active instanceof InternalRestoreHandle restore
                ? Optional.of(restore)
                : Optional.empty();
    }

    boolean adoptRemoteRestore(UUID requestId) {
        RestoreOperationHandle handle;
        RestoreRequest request = RestoreRequest.latest("folderrewind:remote").immediate();
        synchronized (this) {
            if (closed || !serverAvailable.getAsBoolean() || hasActiveOperation()) {
                return false;
            }
            handle = new RestoreOperationHandle(requestId, request, OperationPhase.RUNNING);
            handle.bindControls(
                    () -> Duration.ZERO,
                    () -> RestoreControlResult.ALREADY_SUBMITTED,
                    () -> RestoreControlResult.ALREADY_SUBMITTED);
            active = handle;
        }
        return true;
    }

    void completeDedicatedHandoff() {
        RestoreOperationHandle restore;
        synchronized (this) {
            restore = active instanceof RestoreOperationHandle candidate ? candidate : null;
        }
        if (restore != null) {
            finishRestore(restore, RestoreResult.Outcome.RESTART_HANDOFF_ACCEPTED, null);
        }
    }

    synchronized OperationPresentation activePresentation() {
        if (active instanceof RestoreOperationHandle restore) {
            return restore.request().presentation();
        }
        if (active instanceof BackupOperationHandle backup) {
            return backup.request().presentation();
        }
        return OperationPresentation.defaults();
    }

    void handleSignal(Map<String, String> fields) {
        Objects.requireNonNull(fields, "fields");
        String event = fields.get("event");
        if (event == null) {
            return;
        }

        AbstractOperationHandle<?> current;
        synchronized (this) {
            current = active;
        }
        if (current != null && !matchesRequest(current, fields)) {
            return;
        }
        if (current instanceof BackupOperationHandle backup) {
            handleBackupSignal(backup, fields, event);
        } else if (current instanceof RestoreOperationHandle restore) {
            handleRestoreSignal(restore, fields, event);
        }
    }

    void failActiveBackupTimeout() {
        failActiveBackup(
                OperationFailure.Code.SAVE_TIMEOUT,
                "Timed out waiting for a hot backup to finish");
    }

    void failActiveBackup(OperationFailure.Code code, String message) {
        BackupOperationHandle backup;
        synchronized (this) {
            if (!(active instanceof BackupOperationHandle current)) {
                return;
            }
            backup = current;
        }
        failBackup(backup, code, message);
    }

    void failActiveRestore(OperationFailure.Code code, String message) {
        RestoreOperationHandle restore;
        synchronized (this) {
            if (!(active instanceof RestoreOperationHandle current)) {
                return;
            }
            restore = current;
        }
        failRestore(restore, code, message);
    }

    void completeClientRejoin(boolean success, String reason) {
        RestoreOperationHandle restore;
        synchronized (this) {
            if (!(active instanceof RestoreOperationHandle current)
                    || current.phase().isTerminal()) {
                return;
            }
            restore = current;
        }
        if (success) {
            finishRestore(restore, RestoreResult.Outcome.RESTORED, null);
        } else {
            OperationFailure failure = new OperationFailure(
                    OperationFailure.Code.REJOIN_FAILED,
                    reason == null ? "World restored, but rejoin failed" : reason);
            finishRestore(restore, RestoreResult.Outcome.RESTORED_REJOIN_FAILED, failure);
        }
    }

    void serverStopping(boolean preserveActiveRestore) {
        AbstractOperationHandle<?> current;
        synchronized (this) {
            current = active;
        }
        if (current instanceof RestoreOperationHandle restore
                && preserveActiveRestore
                && (restore.phase() == OperationPhase.SUBMITTING
                    || restore.phase() == OperationPhase.RUNNING)) {
            return;
        }
        failForShutdown(current, "Minecraft server stopped");
    }

    private void submitBackup(BackupOperationHandle handle, KnotLinkRequest command) {
        knotLink.query(command).whenComplete((response, error) -> {
            if (error != null) {
                OperationFailure failure = KnotLinkCommunicationException.failure(error);
                failBackup(handle, failure.code(), failure.message());
            } else if (!response.isOk()) {
                failBackup(handle, OperationFailure.Code.BACKEND_REJECTED, response.displayMessage());
            } else {
                handle.transition(OperationPhase.RUNNING);
            }
        });
    }

    private void submitRestore(RestoreOperationHandle handle) {
        synchronized (this) {
            if (active != handle || handle.phase().isTerminal() || !handle.claimSubmission()) {
                return;
            }
            if (handle.phase() == OperationPhase.COUNTING_DOWN) {
                handle.transition(OperationPhase.SUBMITTING);
            }
            cancelCountdownLocked();
        }
        countdownListener.onSubmitted(handle);

        KnotLinkRequest command = KnotLinkRequest.command("RESTORE")
                .conversation(handle.id())
                .field("current_save", true);
        handle.request().backupId().ifPresent(file -> command.field("file", file.value()));
        handle.request().comment().ifPresent(comment -> command.field("comment", comment));
        handle.request().parameters().forEach(command::field);
        knotLink.query(command).whenComplete((response, error) -> {
            if (error != null) {
                OperationFailure failure = KnotLinkCommunicationException.failure(error);
                failRestore(handle, failure.code(), failure.message());
            } else if (!response.isOk()) {
                failRestore(handle, OperationFailure.Code.BACKEND_REJECTED, response.displayMessage());
            } else {
                handle.transition(OperationPhase.RUNNING);
            }
        });
    }

    private void tickCountdown(RestoreOperationHandle handle) {
        int remaining;
        boolean expired;
        synchronized (this) {
            if (active != handle) return;
            if (handle.phase() != OperationPhase.COUNTING_DOWN) {
                cancelCountdownLocked();
                return;
            }
            remaining = remainingSecondsLocked();
            expired = remaining <= 0;
            if (!expired && remaining == lastAnnouncedSecond) {
                return;
            }
            lastAnnouncedSecond = remaining;
        }
        if (expired) {
            submitRestore(handle);
        } else {
            countdownListener.onTick(handle, remaining);
        }
    }

    private RestoreControlResult confirm(RestoreOperationHandle handle) {
        synchronized (this) {
            if (active != handle) {
                return RestoreControlResult.NOT_PENDING;
            }
            if (handle.phase() != OperationPhase.COUNTING_DOWN) {
                return RestoreControlResult.ALREADY_SUBMITTED;
            }
            handle.transition(OperationPhase.SUBMITTING);
            cancelCountdownLocked();
        }
        countdownListener.onConfirmed(handle);
        submitRestore(handle);
        return RestoreControlResult.CONFIRMED;
    }

    RestoreCancelResult cancelRestore(RestoreCancelRequest request) {
        Objects.requireNonNull(request, "request");
        return switch (cancelMatching(request.requestId())) {
            case CANCELLED -> RestoreCancelResult.CANCELLED;
            case ALREADY_SUBMITTED -> RestoreCancelResult.ALREADY_SUBMITTED;
            default -> RestoreCancelResult.NOT_PENDING;
        };
    }

    private RestoreControlResult cancel(RestoreOperationHandle handle) {
        return cancelMatching(handle.id());
    }

    private RestoreControlResult cancelMatching(UUID requestId) {
        RestoreOperationHandle handle;
        synchronized (this) {
            if (!(active instanceof RestoreOperationHandle restore) || !restore.id().equals(requestId)) {
                return RestoreControlResult.NOT_PENDING;
            }
            handle = restore;
            if (handle.phase().isTerminal()) return RestoreControlResult.NOT_PENDING;
            if (handle.phase() != OperationPhase.COUNTING_DOWN) {
                return RestoreControlResult.ALREADY_SUBMITTED;
            }
            cancelCountdownLocked();
            // Release before completing: a completion callback may immediately submit another request.
            active = null;
            handle.finish(OperationPhase.CANCELLED, new RestoreResult(
                    RestoreResult.Outcome.CANCELLED, handle.request().backupId(), Optional.empty()));
        }
        countdownListener.onCancelled(handle);
        return RestoreControlResult.CANCELLED;
    }

    private Duration remaining(RestoreOperationHandle handle) {
        synchronized (this) {
            if (active != handle || handle.phase() != OperationPhase.COUNTING_DOWN) {
                return Duration.ZERO;
            }
            return Duration.ofSeconds(remainingSecondsLocked());
        }
    }

    private int remainingSecondsLocked() {
        long remainingNanos = Math.max(0L, restoreDeadlineNanos - nanoTime.getAsLong());
        return (int) Math.ceil(remainingNanos / 1_000_000_000.0);
    }

    private synchronized void handleBackupSignal(
            BackupOperationHandle handle,
            Map<String, String> fields,
            String event) {
        if (handle.protectedBackup) {
            if (handle.phase().isTerminal()) return;
            if (!handle.id().toString().equals(fields.get("request_id"))
                    || !KnotLinkRequest.CALLER_ID.equals(fields.get("from"))) return;
            if ("command_failed".equals(event) && commandMatches(fields, "BACKUP")
                    && "canceled".equals(fields.get("reason"))) {
                finishBackup(handle, BackupResult.Outcome.CANCELLED, null,
                        new OperationFailure(OperationFailure.Code.BACKEND_CANCELLED, "Protected backup cancelled"));
                return;
            }
            if ("backup_success".equals(event)) return; // Per-source event precedes the protected transaction receipt.
            if ("command_completed".equals(event) && commandMatches(fields, "BACKUP")) {
                String result = fields.get("result");
                if (!"true".equals(fields.get("important")) || fields.getOrDefault("file", "").isBlank()
                        || !("created".equals(result) || "reused".equals(result))) {
                    failBackup(handle, OperationFailure.Code.PROTOCOL_ERROR, "Protected backup was not confirmed");
                } else {
                    handle.protectionConfirmed = true; handle.reused = "reused".equals(result);
                    finishBackup(handle, BackupResult.Outcome.CREATED, fields.get("file"), null);
                }
                return;
            }
        }
        if ("backup_success".equals(event)) {
            finishBackup(handle, BackupResult.Outcome.CREATED, fields.get("file"), null);
            return;
        }
        if ("backup_failed".equals(event)) {
            failBackup(
                    handle,
                    OperationFailure.Code.BACKEND_REJECTED,
                    firstNonBlank(fields.get("error"), fields.get("message")));
            return;
        }
        if ("command_failed".equals(event) && commandMatches(fields, "BACKUP")) {
            failBackup(
                    handle,
                    OperationFailure.Code.BACKEND_REJECTED,
                    firstNonBlank(fields.get("error"), fields.get("message")));
            return;
        }
        if ("command_completed".equals(event) && commandMatches(fields, "BACKUP")) {
            if ("no_changes".equalsIgnoreCase(fields.get("result"))) {
                finishBackup(handle, BackupResult.Outcome.NO_CHANGES, null, null);
            } else {
                finishBackup(handle, BackupResult.Outcome.CREATED, fields.get("file"), null);
            }
        }
    }

    private void handleRestoreSignal(
            RestoreOperationHandle handle,
            Map<String, String> fields,
            String event) {
        if ("restore_cancelled".equals(event)) {
            failRestore(
                    handle,
                    OperationFailure.Code.BACKEND_CANCELLED,
                    firstNonBlank(fields.get("reason"), fields.get("message")));
            return;
        }
        if ("restore_finished".equals(event)
                && !"success".equalsIgnoreCase(fields.getOrDefault("status", "failure"))) {
            failRestore(
                    handle,
                    OperationFailure.Code.RESTORE_FAILED,
                    firstNonBlank(fields.get("reason"), fields.get("error")));
            return;
        }
        if ("command_failed".equals(event) && commandMatches(fields, "RESTORE")) {
            failRestore(
                    handle,
                    OperationFailure.Code.BACKEND_REJECTED,
                    firstNonBlank(fields.get("error"), fields.get("message")));
            return;
        }
        if ("hot_restore_complete".equals(event)) {
            String status = fields.getOrDefault("status", "restore_ok_rejoin_failed");
            if ("full_success".equalsIgnoreCase(status)) {
                finishRestore(handle, RestoreResult.Outcome.RESTORED, null);
            } else {
                finishRestore(
                        handle,
                        RestoreResult.Outcome.RESTORED_REJOIN_FAILED,
                        new OperationFailure(OperationFailure.Code.REJOIN_FAILED, status));
            }
        }
    }

    private boolean commandMatches(Map<String, String> fields, String expected) {
        String command = fields.get("command");
        return expected.equalsIgnoreCase(command);
    }

    private static boolean matchesRequest(
            AbstractOperationHandle<?> handle,
            Map<String, String> fields) {
        String requestId = fields.get("request_id");
        return requestId == null
                || requestId.isBlank()
                || handle.id().toString().equalsIgnoreCase(requestId);
    }

    private void finishBackup(
            BackupOperationHandle handle,
            BackupResult.Outcome outcome,
            String file,
            OperationFailure failure) {
        OperationPhase phase = outcome == BackupResult.Outcome.CANCELLED
                ? OperationPhase.CANCELLED
                : failure == null ? OperationPhase.SUCCEEDED : OperationPhase.FAILED;
        Optional<BackupId> backupId;
        try {
            backupId = Optional.ofNullable(file).map(BackupId::of);
        } catch (IllegalArgumentException exception) {
            failBackup(handle, OperationFailure.Code.PROTOCOL_ERROR, exception.getMessage());
            return;
        }
        BackupResult result = new BackupResult(outcome, backupId, Optional.ofNullable(failure));
        if (handle.finish(phase, result)) {
            release(handle);
        }
    }

    private void failBackup(
            BackupOperationHandle handle,
            OperationFailure.Code code,
            String message) {
        OperationFailure failure = new OperationFailure(code, safeMessage(message, code));
        BackupResult.Outcome outcome = code == OperationFailure.Code.BUSY
                || code == OperationFailure.Code.NO_ACTIVE_SERVER
                ? BackupResult.Outcome.REJECTED
                : BackupResult.Outcome.FAILED;
        OperationPhase phase = outcome == BackupResult.Outcome.REJECTED
                ? OperationPhase.REJECTED
                : OperationPhase.FAILED;
        BackupResult result = new BackupResult(outcome, Optional.empty(), Optional.of(failure));
        if (handle.finish(phase, result)) {
            release(handle);
        }
    }

    private void finishRestore(
            RestoreOperationHandle handle,
            RestoreResult.Outcome outcome,
            OperationFailure failure) {
        OperationPhase phase = switch (outcome) {
            case RESTORED, RESTORED_REJOIN_FAILED, RESTART_HANDOFF_ACCEPTED -> OperationPhase.SUCCEEDED;
            case CANCELLED -> OperationPhase.CANCELLED;
            case REJECTED -> OperationPhase.REJECTED;
            case FAILED -> OperationPhase.FAILED;
        };
        RestoreResult result = new RestoreResult(
                outcome,
                handle.request().backupId(),
                Optional.ofNullable(failure));
        if (handle.finish(phase, result)) {
            release(handle);
        }
    }

    private void failRestore(
            RestoreOperationHandle handle,
            OperationFailure.Code code,
            String message) {
        OperationFailure failure = new OperationFailure(code, safeMessage(message, code));
        RestoreResult result = new RestoreResult(
                RestoreResult.Outcome.FAILED,
                handle.request().backupId(),
                Optional.of(failure));
        if (handle.finish(OperationPhase.FAILED, result)) {
            release(handle);
        }
    }

    private BackupOperationHandle rejectedBackup(
            BackupRequest request,
            OperationFailure.Code code,
            String message) {
        BackupOperationHandle handle = new BackupOperationHandle(
                UUID.randomUUID(),
                request,
                OperationPhase.SUBMITTING);
        OperationFailure failure = new OperationFailure(code, message);
        handle.finish(
                OperationPhase.REJECTED,
                new BackupResult(
                        BackupResult.Outcome.REJECTED,
                        Optional.empty(),
                        Optional.of(failure)));
        return handle;
    }

    private RestoreOperationHandle rejectedRestore(
            RestoreRequest request,
            OperationFailure.Code code,
            String message) {
        RestoreOperationHandle handle = new RestoreOperationHandle(
                UUID.randomUUID(),
                request,
                OperationPhase.SUBMITTING);
        handle.bindControls(
                () -> Duration.ZERO,
                () -> RestoreControlResult.NOT_PENDING,
                () -> RestoreControlResult.NOT_PENDING);
        OperationFailure failure = new OperationFailure(code, message);
        handle.finish(
                OperationPhase.REJECTED,
                new RestoreResult(
                        RestoreResult.Outcome.REJECTED,
                        request.backupId(),
                        Optional.of(failure)));
        return handle;
    }

    private void failForShutdown(AbstractOperationHandle<?> current, String message) {
        if (current instanceof BackupOperationHandle backup) {
            failBackup(backup, OperationFailure.Code.SERVER_STOPPED, message);
        } else if (current instanceof RestoreOperationHandle restore) {
            failRestore(restore, OperationFailure.Code.SERVER_STOPPED, message);
        } else if (current instanceof BackupProtectionHandle protection) {
            failProtection(protection, OperationFailure.Code.SERVER_STOPPED, message);
        } else if (current instanceof CatalogOperationHandle catalog) {
            BackupCatalogResult result = BackupCatalogResult.failed(
                    BackupCatalogResult.Outcome.FAILED,
                    new OperationFailure(OperationFailure.Code.SERVER_STOPPED, message));
            if (catalog.finish(OperationPhase.FAILED, result)) {
                release(catalog);
            }
        }
    }

    OperationHandle<BackupProtectionResult> protection(BackupProtectionRequest request, boolean set) {
        Objects.requireNonNull(request);
        var handle = new BackupProtectionHandle(request);
        synchronized (this) {
            if (closed || !serverAvailable.getAsBoolean()) {
                failProtection(handle, OperationFailure.Code.NO_ACTIVE_SERVER, "No active Minecraft server"); return handle;
            }
            if (hasActiveOperation()) {
                failProtection(handle, OperationFailure.Code.BUSY, "Another current-world operation is active"); return handle;
            }
            active = handle;
        }
        String command = set ? "MARK_IMPORTANT" : "GET_IMPORTANCE";
        discoverProtection(handle, command, false, () -> {
            var wire = KnotLinkRequest.command(command).conversation(handle.id()).field("current_save", true)
                    .field("file", request.backupId().value());
            if (set) wire.field("important", request.important());
            knotLink.query(wire).whenComplete((response, error) -> {
                synchronized (this) {
                    if (handle.phase().isTerminal()) return;
                    if (error != null) { var f = KnotLinkCommunicationException.failure(error); failProtection(handle, f.code(), f.message()); return; }
                    if (response == null || !response.isOk()) { failProtection(handle, OperationFailure.Code.BACKEND_REJECTED, response == null ? "Missing response" : response.displayMessage()); return; }
                    var fields = response.fields();
                    var flag = fields.get("important");
                    if (!handle.id().toString().equals(fields.get("request_id"))
                            || !KnotLinkRequest.CALLER_ID.equals(fields.get("from"))
                            || !request.backupId().value().equals(fields.get("file"))
                            || !("true".equals(flag) || "false".equals(flag))
                            || set && Boolean.parseBoolean(flag) != request.important()) {
                        failProtection(handle, OperationFailure.Code.PROTOCOL_ERROR, "Invalid protection receipt"); return;
                    }
                    if (handle.finish(OperationPhase.SUCCEEDED, new BackupProtectionResult(BackupProtectionResult.Outcome.SUCCESS,
                            request.backupId(), Optional.of(Boolean.parseBoolean(flag)), Optional.empty()))) release(handle);
                }
            });
        });
        return handle;
    }

    private void discoverProtection(AbstractOperationHandle<?> handle, String command, boolean requireProtect, Runnable submit) {
        try {
        capabilities.get().whenComplete((response, error) -> {
            synchronized (this) {
                if (handle.phase().isTerminal() || active != handle) return;
                try {
                    if (error != null) { var f = KnotLinkCommunicationException.failure(error); failProtectionOperation(handle, f.code(), f.message()); return; }
                    if (response == null || response.outcome() != com.leafuke.minebackup.api.v2.BackendCapabilitiesResult.Outcome.SUCCESS) { failProtectionOperation(handle, response != null && response.outcome() == com.leafuke.minebackup.api.v2.BackendCapabilitiesResult.Outcome.FAILED ? OperationFailure.Code.COMMUNICATION_ERROR : OperationFailure.Code.UNSUPPORTED, response == null ? "Missing capability response" : response.detail()); return; }
                    var declaration = response.commands().get(command);
                    if (declaration == null || requireProtect && !declaration.parameters().containsKey("protect")) {
                        failProtectionOperation(handle, OperationFailure.Code.UNSUPPORTED, "Backend does not support " + command + " protection"); return;
                    }
                    submit.run();
                } catch (RuntimeException invalid) { failProtectionOperation(handle, OperationFailure.Code.PROTOCOL_ERROR, invalid.getMessage()); }
            }
        });
        } catch (RuntimeException unavailable) {
            failProtectionOperation(handle, OperationFailure.Code.COMMUNICATION_ERROR, unavailable.getMessage());
        }
    }

    private void failProtectionOperation(AbstractOperationHandle<?> handle, OperationFailure.Code code, String message) {
        if (handle instanceof BackupOperationHandle backup) failBackup(backup, code, message);
        else failProtection((BackupProtectionHandle) handle, code, message);
    }

    private void failProtection(BackupProtectionHandle handle, OperationFailure.Code code, String message) {
        var outcome = code == OperationFailure.Code.UNSUPPORTED ? BackupProtectionResult.Outcome.UNSUPPORTED
                : code == OperationFailure.Code.BUSY || code == OperationFailure.Code.NO_ACTIVE_SERVER
                ? BackupProtectionResult.Outcome.REJECTED : BackupProtectionResult.Outcome.FAILED;
        if (handle.finish(outcome == BackupProtectionResult.Outcome.REJECTED || outcome == BackupProtectionResult.Outcome.UNSUPPORTED
                ? OperationPhase.REJECTED : OperationPhase.FAILED,
                new BackupProtectionResult(outcome, handle.request.backupId(), Optional.empty(), Optional.of(new OperationFailure(code, message))))) release(handle);
    }

    private synchronized boolean hasActiveOperation() {
        return active != null && !active.phase().isTerminal();
    }

    private void release(AbstractOperationHandle<?> handle) {
        synchronized (this) {
            if (active == handle) {
                cancelCountdownLocked();
                active = null;
            }
        }
    }

    private void cancelCountdownLocked() {
        ScheduledFuture<?> future = countdownFuture;
        countdownFuture = null;
        restoreDeadlineNanos = 0L;
        lastAnnouncedSecond = -1;
        if (future != null) {
            future.cancel(false);
        }
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }

    private static String safeMessage(String message, OperationFailure.Code code) {
        return message == null || message.isBlank() ? code.name() : message;
    }

    @Override
    public void close() {
        AbstractOperationHandle<?> current;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            cancelCountdownLocked();
            current = active;
        }
        failForShutdown(current, "MineBackup runtime closed");
    }
}
