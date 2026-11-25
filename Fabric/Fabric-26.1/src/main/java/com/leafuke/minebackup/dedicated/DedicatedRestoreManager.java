package com.leafuke.minebackup.dedicated;

import com.leafuke.minebackup.MineBackup;
import com.leafuke.minebackup.api.v2.DedicatedRestoreStatus;
import com.leafuke.minebackup.config.Config;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
            Path root=store.directory().resolve("v3");
            if(Files.isDirectory(root)){
                try(var dirs=Files.newDirectoryStream(root)){
                    for(Path entry:dirs){
                        var result=entry.resolve("result.json");if(!Files.isRegularFile(result))continue;
                        var updated=Files.getLastModifiedTime(result).toInstant();
                        if(lastStatus.isPresent()&&!lastStatus.get().updatedAt().isBefore(updated))continue;
                        var value=com.leafuke.minebackup.interop.InteropFiles.readObject(result);
                        String outcome=com.leafuke.minebackup.interop.InteropWire.string(value,"outcome");
                        var state=switch(outcome){case "scriptStarted"->DedicatedRestoreStatus.State.RESTART_STARTED;case "failed"->DedicatedRestoreStatus.State.RESTART_FAILED;default->DedicatedRestoreStatus.State.UNCERTAIN;};
                        lastStatus=Optional.of(new DedicatedRestoreStatus(com.leafuke.minebackup.interop.InteropWire.uuid(value,"operationId"),state,updated,Optional.of(outcome)));
                    }
                }
            }
            // A new server may start while an older sidecar still owns its handoff.
            // Only its owner is allowed to remove or finalize live records.
        } catch (Exception exception) {
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

    public Optional<DedicatedRestoreStatus> lastStatus() {
        return lastStatus;
    }

    public record InteropHandoff(Path directory, UUID id, Process child) {
        public boolean commit() {
            if (!child.isAlive()) return false;
            try {
                com.leafuke.minebackup.interop.InteropFiles.writeObject(
                        directory.resolve("committed.json"),
                        com.leafuke.minebackup.interop.InteropWire.object("handoffId", id));
                return true;
            } catch (IOException failure) {
                return false;
            }
        }

        public void abandon() {
            child.destroyForcibly();
        }
    }

    public InteropHandoff startInterop(
            Config.DedicatedRestore config,
            Path cwd,
            Path world,
            List<String> restart,
            com.leafuke.minebackup.interop.InteropClient.Binding binding,
            com.leafuke.minebackup.interop.InteropSnapshot snapshot,
            java.util.function.BooleanSupplier live)
            throws Exception {
        UUID handoff = UUID.randomUUID();
        Path dir = store.directory().resolve("v3").resolve(handoff.toString());
        if (dir.toAbsolutePath().normalize().startsWith(world.toRealPath()))
            throw new IOException("unsafe_handoff_directory");
        Files.createDirectories(dir);
        var command = new com.google.gson.JsonArray();
        restart.forEach(command::add);
        var record =
                com.leafuke.minebackup.interop.InteropWire.object(
                        "operationId",
                        snapshot.operation(),
                        "targetRef",
                        snapshot.target(),
                        "instanceId",
                        binding.endpoint().instance(),
                        "domainId",
                        binding.endpoint().domain(),
                        "product",binding.endpoint().product(),
                        "sessionId",
                        binding.world().session(),
                        "stepId",
                        snapshot.step().id(),
                        "handoffId",
                        handoff,
                        "pid",
                        ProcessHandle.current().pid(),
                        "world",
                        world.toString(),
                        "command",
                        command,
                        "workingDirectory",
                        cwd.toString(),
                        "timeout",
                        config.operationTimeoutSeconds(),
                        "releaseTimeout",
                        config.worldReleaseTimeoutSeconds());
        com.leafuke.minebackup.interop.InteropFiles.writeObject(
                dir.resolve("handoff.json"), record);
        var launch = new ArrayList<>(sidecarCommand(dir));
        var gson = com.google.gson.Gson.class.getProtectionDomain().getCodeSource().getLocation();
        Path gsonPath = Path.of(gson.toURI());
        if (!Files.exists(gsonPath)) throw new IOException("missing_sidecar_codec");
        launch.set(2, sidecarClasspath() + java.io.File.pathSeparator + gsonPath);

        Process process =
                SidecarProcess.start(
                        () -> new ProcessBuilder(launch).inheritIO().start(),
                        () -> {
                            Path ready = dir.resolve("ready.json");
                            return Files.isRegularFile(ready)
                                    && com.leafuke.minebackup.interop.InteropWire.uuid(
                                                    com.leafuke.minebackup.interop.InteropFiles
                                                            .readObject(ready),
                                                    "handoffId")
                                            .equals(handoff);
                        },
                        live,
                        java.time.Duration.ofSeconds(config.sidecarStartTimeoutSeconds()),
                        System::nanoTime,
                        () -> Thread.sleep(50));
        return new InteropHandoff(dir, handoff, process);
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
            var origins = FabricLoader.getInstance().getModContainer(MineBackup.MOD_ID)
                    .map(container -> container.getOrigin().getPaths()).orElse(List.of());
            if (!origins.isEmpty()) return SidecarClasspath.resolve(origins);
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

}
