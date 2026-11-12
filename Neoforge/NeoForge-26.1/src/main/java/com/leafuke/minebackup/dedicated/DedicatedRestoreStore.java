package com.leafuke.minebackup.dedicated;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

public final class DedicatedRestoreStore {
    public static final String ACTIVE_FILE = "active.properties";
    public static final String LAST_RESULT_FILE = "last-result.properties";
    public static final String READY_FILE = "sidecar.ready";

    private final Path directory;
    private String wireRequestId;
    private String wireFrom;
    private boolean wireIdentityRecorded;

    public DedicatedRestoreStore(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    public Path directory() {
        return directory;
    }

    public Path activePath() {
        return directory.resolve(ACTIVE_FILE);
    }

    public Path readyPath() {
        return directory.resolve(READY_FILE);
    }

    public void writeActive(DedicatedRestoreSession session) throws IOException {
        write(activePath(), session);
    }

    public void writeActive(DedicatedRestoreSession session, String requestId, String from) throws IOException {
        wireIdentityRecorded = true;
        wireRequestId = requestId;
        wireFrom = from;
        writeActive(session);
    }

    String signalRequestId(DedicatedRestoreSession session) {
        return wireIdentityRecorded ? wireRequestId : session.requestId().toString();
    }

    String signalFrom() { return wireFrom; }

    public void writeFinal(DedicatedRestoreSession session) throws IOException {
        write(directory.resolve(LAST_RESULT_FILE), session);
        Files.deleteIfExists(activePath());
        Files.deleteIfExists(readyPath());
    }

    public Optional<DedicatedRestoreSession> readActive() throws IOException {
        return read(activePath());
    }

    public Optional<DedicatedRestoreSession> readLastResult() throws IOException {
        return read(directory.resolve(LAST_RESULT_FILE));
    }

    public void markReady() throws IOException {
        Files.createDirectories(directory);
        Files.writeString(
                readyPath(),
                Instant.now().toString(),
                StandardCharsets.UTF_8);
    }

    public void markReady(UUID requestId) throws IOException {
        Files.writeString(readyPath(), requestId.toString(), StandardCharsets.UTF_8);
    }

    public boolean readyFor(UUID requestId) throws IOException {
        return Files.isRegularFile(readyPath())
                && Files.readString(readyPath(), StandardCharsets.UTF_8).equals(requestId.toString());
    }

    public void clearTransient() throws IOException {
        Files.deleteIfExists(activePath());
        Files.deleteIfExists(readyPath());
    }

    private void write(Path target, DedicatedRestoreSession session) throws IOException {
        Files.createDirectories(target.getParent());
        Properties values = new Properties();
        values.setProperty("requestId", session.requestId().toString());
        values.setProperty("callerId", session.callerId());
        values.setProperty("worldId", session.worldId());
        values.setProperty("worldPath", session.worldPath().toString());
        values.setProperty("workingDirectory", session.workingDirectory().toString());
        values.setProperty("parentPid", String.valueOf(session.parentPid()));
        values.setProperty("worldReleaseTimeoutSeconds", String.valueOf(session.worldReleaseTimeoutSeconds()));
        values.setProperty("operationTimeoutSeconds", String.valueOf(session.operationTimeoutSeconds()));
        values.setProperty("state", session.state().name());
        values.setProperty("updatedAt", session.updatedAt().toString());
        values.setProperty("detail", session.detail());
        if (wireIdentityRecorded) {
            values.setProperty("wireIdentityRecorded", "true");
            if (wireRequestId != null) values.setProperty("wireRequestId", wireRequestId);
            if (wireFrom != null) values.setProperty("wireFrom", wireFrom);
        }
        values.setProperty("restartCommand.count", String.valueOf(session.restartCommand().size()));
        for (int index = 0; index < session.restartCommand().size(); index++) {
            values.setProperty("restartCommand." + index, session.restartCommand().get(index));
        }
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            values.store(writer, "MineBackup dedicated restore handoff");
        }
        try {
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Optional<DedicatedRestoreSession> read(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        Properties values = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            values.load(reader);
        }
        wireIdentityRecorded = Boolean.parseBoolean(values.getProperty("wireIdentityRecorded", "false"));
        wireRequestId = values.getProperty("wireRequestId");
        wireFrom = values.getProperty("wireFrom");
        int count = Integer.parseInt(values.getProperty("restartCommand.count"));
        List<String> command = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            command.add(require(values, "restartCommand." + index));
        }
        return Optional.of(new DedicatedRestoreSession(
                UUID.fromString(require(values, "requestId")),
                require(values, "callerId"),
                require(values, "worldId"),
                Path.of(require(values, "worldPath")),
                Path.of(require(values, "workingDirectory")),
                command,
                Long.parseLong(require(values, "parentPid")),
                Integer.parseInt(require(values, "worldReleaseTimeoutSeconds")),
                Integer.parseInt(require(values, "operationTimeoutSeconds")),
                DedicatedRestoreSession.State.valueOf(require(values, "state")),
                Instant.parse(require(values, "updatedAt")),
                values.getProperty("detail", "")));
    }

    private static String require(Properties values, String key) throws IOException {
        String value = values.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IOException("Missing dedicated restore session field: " + key);
        }
        return value;
    }
}
