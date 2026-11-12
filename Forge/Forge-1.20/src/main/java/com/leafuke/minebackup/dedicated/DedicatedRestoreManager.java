package com.leafuke.minebackup.dedicated;

import com.leafuke.minebackup.MineBackup;
import com.leafuke.minebackup.api.v2.DedicatedRestoreStatus;
import com.leafuke.minebackup.config.Config;
import net.minecraftforge.fml.ModList;

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
    private final java.util.concurrent.atomic.AtomicReference<Handoff> pending = new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.concurrent.ConcurrentMap<UUID, Process> children = new java.util.concurrent.ConcurrentHashMap<>();

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
        return prepare(config, workingDirectory, worldPath, worldId, requestId, callerId,
                requestId.toString(), com.leafuke.minebackup.knotlink.protocol.KnotLinkRequest.CALLER_ID, () -> true);
    }

    public synchronized Handoff prepare(Config.DedicatedRestore config, Path workingDirectory, Path worldPath,
            String worldId, UUID requestId, String callerId, String wireRequestId, String wireFrom,
            java.util.function.BooleanSupplier current) {
        if (!current.getAsBoolean()) return Handoff.failed("Restore session ended before sidecar startup");
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
        Process process = null;
        boolean accepted = false;
        try {
            store.clearTransient();
            store.writeActive(session, wireRequestId, wireFrom);
            List<String> command = sidecarCommand(store.directory());
            MineBackup.LOGGER.info("Starting dedicated restore sidecar from {}", command.get(2));
            process = SidecarProcess.start(() -> {
                        Process child = new ProcessBuilder(command).inheritIO().start();
                        children.put(requestId, child);
                        if (!current.getAsBoolean()) child.destroyForcibly();
                        return child;
                    },
                    () -> store.readyFor(requestId), current,
                    java.time.Duration.ofSeconds(config.sidecarStartTimeoutSeconds()), System::nanoTime,
                    () -> Thread.sleep(50L));
            Handoff handoff = new Handoff(true, "", session, process);
            pending.set(handoff);
            accepted = true;
            return handoff;
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            return Handoff.failed(exception.getMessage());
        } finally {
            if (!accepted) discard(new Handoff(false, "", session, process));
        }
    }

    public synchronized void discard(Handoff handoff) {
        if (handoff == null || handoff.session() == null) return;
        pending.compareAndSet(handoff, null);
        Process process = handoff.sidecar();
        if (process == null) process = children.get(handoff.session().requestId());
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
            try { process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            if (process.isAlive()) return; // Do not erase ownership while a child may still write.
        }
        children.remove(handoff.session().requestId());
        try {
            if (store.readActive().map(value -> value.requestId().equals(handoff.session().requestId())).orElse(false))
                store.clearTransient();
        } catch (IOException | RuntimeException exception) {
            MineBackup.LOGGER.warn("Could not clean up failed sidecar handoff", exception);
        }
    }

    /** Transfers child ownership immediately before halt; never waits on the game thread. */
    public boolean claim(Handoff handoff) {
        if (handoff == null || handoff.sidecar() == null || !handoff.sidecar().isAlive()
                || !pending.compareAndSet(handoff, null)) return false;
        children.remove(handoff.session().requestId(), handoff.sidecar());
        return true;
    }

    public Handoff cancelPending(UUID requestId) {
        children.forEach((id, child) -> {
            if (requestId == null || requestId.equals(id)) child.destroyForcibly();
        });
        Handoff handoff = pending.get();
        if (handoff != null && (requestId == null || requestId.equals(handoff.session().requestId()))
                && pending.compareAndSet(handoff, null)) return handoff;
        return null;
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
        command.add(sidecarClasspath());
        command.add(DedicatedRestoreSidecar.class.getName());
        command.add(restartDirectory.toString());
        return List.copyOf(command);
    }

    static String sidecarClasspath() {
        try {
            var mods = ModList.get();
            if (mods != null && mods.getModFileById(MineBackup.MOD_ID) != null)
                return SidecarClasspath.resolve(List.of(mods.getModFileById(MineBackup.MOD_ID).getFile().getFilePath()));
        } catch (RuntimeException exception) {
            org.slf4j.LoggerFactory.getLogger("minebackup").debug("Cannot resolve physical mod origin", exception);
        }
        // Development/test output directories have an ordinary file: code source.
        try {
            var source = DedicatedRestoreSidecar.class.getProtectionDomain().getCodeSource();
            if (source != null && "file".equalsIgnoreCase(source.getLocation().getProtocol()))
                return SidecarClasspath.resolve(List.of(Path.of(source.getLocation().toURI())));
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot resolve physical sidecar classpath", exception);
        }
        throw new IllegalStateException("Cannot resolve physical sidecar classpath");
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
