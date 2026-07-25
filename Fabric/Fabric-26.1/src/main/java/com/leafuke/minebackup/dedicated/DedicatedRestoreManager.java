package com.leafuke.minebackup.dedicated;

import com.leafuke.minebackup.MineBackup;
import com.leafuke.minebackup.api.v2.DedicatedRestoreStatus;
import com.leafuke.minebackup.config.Config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class DedicatedRestoreManager {
    private final DedicatedRestoreStore store;
    private volatile Optional<DedicatedRestoreStatus> lastStatus = Optional.empty();

    public DedicatedRestoreManager(Path restartDirectory) {
        store = new DedicatedRestoreStore(restartDirectory);
    }

    public void loadLastResult() {
        try {
            lastStatus = store.readLastResult().flatMap(DedicatedRestoreManager::toStatus);
            Files.deleteIfExists(store.readyPath());
            for (int attempt = 0; attempt < 20 && store.readActive().isPresent(); attempt++) {
                Thread.sleep(50L);
            }
            if (store.readActive().isPresent()) {
                DedicatedRestoreSession stale = store.readActive().orElseThrow()
                        .withState(
                                DedicatedRestoreSession.State.UNCERTAIN,
                                "Previous server started with an unfinished handoff");
                store.writeFinal(stale);
                lastStatus = toStatus(stale);
            }
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            MineBackup.LOGGER.error("Failed to load dedicated restore handoff state", exception);
        }
    }

    public Availability availability(Config.DedicatedRestore config, Path workingDirectory) {
        RestartScriptResolver.Resolution resolution = RestartScriptResolver.resolve(
                config, workingDirectory, System.getProperty("os.name", ""));
        if (!resolution.available()) {
            return new Availability(false, resolution.reason(), resolution);
        }
        try {
            Files.createDirectories(store.directory());
            if (!Files.isDirectory(store.directory()) || !Files.isWritable(store.directory())) {
                return new Availability(
                        false, "Restart session directory is not writable", resolution);
            }
        } catch (IOException exception) {
            return new Availability(false, exception.getMessage(), resolution);
        }
        return new Availability(true, "", resolution);
    }

    public Handoff prepare(
            Config.DedicatedRestore config,
            Path workingDirectory,
            Path worldPath,
            String worldId,
            UUID requestId,
            String callerId) {
        Availability availability = availability(config, workingDirectory);
        if (!availability.available()) {
            return Handoff.failed(availability.reason());
        }
        Path normalizedWorld = worldPath.toAbsolutePath().normalize();
        if (store.directory().startsWith(normalizedWorld)) {
            return Handoff.failed("Restart session directory must be outside the world save");
        }
        DedicatedRestoreSession session = new DedicatedRestoreSession(
                requestId,
                callerId,
                worldId,
                normalizedWorld,
                workingDirectory,
                availability.resolution().command(),
                ProcessHandle.current().pid(),
                config.worldReleaseTimeoutSeconds(),
                config.operationTimeoutSeconds(),
                DedicatedRestoreSession.State.PREPARED,
                Instant.now(),
                "");
        try {
            store.clearTransient();
            store.writeActive(session);
            Process process = new ProcessBuilder(sidecarCommand(store.directory()))
                    .inheritIO()
                    .start();
            long deadline = System.nanoTime()
                    + java.time.Duration.ofSeconds(config.sidecarStartTimeoutSeconds()).toNanos();
            while (System.nanoTime() < deadline) {
                if (Files.isRegularFile(store.readyPath())) {
                    return new Handoff(true, "", session, process);
                }
                if (!process.isAlive()) {
                    store.clearTransient();
                    return Handoff.failed("Dedicated restore sidecar exited before becoming ready");
                }
                Thread.sleep(50L);
            }
            process.destroyForcibly();
            process.waitFor();
            store.clearTransient();
            return Handoff.failed("Timed out waiting for dedicated restore sidecar");
        } catch (Exception exception) {
            try {
                store.clearTransient();
            } catch (IOException cleanupError) {
                exception.addSuppressed(cleanupError);
            }
            return Handoff.failed(exception.getMessage());
        }
    }

    public Optional<DedicatedRestoreStatus> lastStatus() {
        return lastStatus;
    }

    static List<String> sidecarCommand(Path restartDirectory) {
        String executable = Path.of(
                        System.getProperty("java.home"),
                        "bin",
                        System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                                ? "java.exe"
                                : "java")
                .toString();
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(DedicatedRestoreSidecar.class.getName());
        command.add(restartDirectory.toString());
        return List.copyOf(command);
    }

    private static Optional<DedicatedRestoreStatus> toStatus(DedicatedRestoreSession session) {
        DedicatedRestoreStatus.State state = switch (session.state()) {
            case RESTORE_FAILED -> DedicatedRestoreStatus.State.FAILED;
            case RESTORE_CANCELLED -> DedicatedRestoreStatus.State.CANCELLED;
            case RESTART_STARTED -> DedicatedRestoreStatus.State.RESTART_STARTED;
            case RESTART_FAILED -> DedicatedRestoreStatus.State.RESTART_FAILED;
            case UNCERTAIN -> DedicatedRestoreStatus.State.UNCERTAIN;
            default -> null;
        };
        return state == null
                ? Optional.empty()
                : Optional.of(new DedicatedRestoreStatus(
                        session.requestId(),
                        state,
                        session.updatedAt(),
                        Optional.ofNullable(session.detail()).filter(value -> !value.isBlank())));
    }

    public record Availability(
            boolean available,
            String reason,
            RestartScriptResolver.Resolution resolution) {
    }

    public record Handoff(
            boolean accepted,
            String reason,
            DedicatedRestoreSession session,
            Process sidecar) {
        static Handoff failed(String reason) {
            return new Handoff(false, reason == null ? "Sidecar handoff failed" : reason, null, null);
        }
    }
}
