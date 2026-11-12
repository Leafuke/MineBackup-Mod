package com.leafuke.minebackup.dedicated;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.nio.file.spi.FileSystemProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class SidecarReliabilityTest {
    @TempDir Path temporary;

    @Test void packagedSidecarLoadsInIndependentJvmFromUnicodePath() throws Exception {
        Path folder = Files.createDirectory(temporary.resolve("mod with spaces 中文"));
        Path jar = Files.copy(Path.of(System.getProperty("minebackup.test.jar")), folder.resolve("minebackup.jar"));
        String classpath = SidecarClasspath.resolve(List.of(jar));
        String executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        Process process = new ProcessBuilder(executable, "-cp", classpath, DedicatedRestoreSidecar.class.getName())
                .redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(2, process.exitValue(), output);
            assertTrue(output.contains("requires the restart session directory"), output);
        } finally { process.destroyForcibly(); }
        try (var archive = FileSystems.newFileSystem(jar)) {
            assertThrows(IllegalStateException.class, () -> SidecarClasspath.resolve(List.of(archive.getPath("/"))));
        }
        // NeoForge/Forge expose the real union provider on the test runtime classpath.
        boolean unionTested = false;
        for (var provider : FileSystemProvider.installedProviders()) {
            if (!provider.getScheme().equals("union")) continue;
            unionTested = true;
            try (var union = provider.newFileSystem(jar, Map.of("additional", List.of(folder)))) {
                Path root = Path.of(union.getRootDirectories().iterator().next().toUri());
                assertTrue(Files.exists(root));
                assertThrows(IllegalStateException.class, () -> SidecarClasspath.resolve(List.of(root)));
                assertEquals(classpath, SidecarClasspath.resolve(List.of(root, jar)));
            }
        }
        if (Boolean.getBoolean("minebackup.test.unionRequired")) assertTrue(unionTested, "Loader union provider must be exercised");
    }

    @Test void startupFailureTimeoutCancellationAndEarlyExitNeverLeaveChildRunning() throws Exception {
        AtomicLong clock = new AtomicLong();
        var process = new FakeProcess();
        assertThrows(IOException.class, () -> SidecarProcess.start(() -> process, () -> false, () -> true,
                Duration.ofSeconds(3), clock::get, () -> clock.addAndGet(Duration.ofSeconds(1).toNanos())));
        assertFalse(process.isAlive());
        var earlyExit = new FakeProcess(); earlyExit.destroy();
        assertThrows(IOException.class, () -> SidecarProcess.start(() -> earlyExit, () -> true, () -> true,
                Duration.ofSeconds(3), clock::get, () -> fail("must not wait after exit")));
        var cancelled = new FakeProcess();
        var current = new AtomicBoolean(true);
        assertThrows(IOException.class, () -> SidecarProcess.start(() -> cancelled, () -> false, current::get,
                Duration.ofSeconds(3), clock::get, () -> current.set(false)));
        assertFalse(cancelled.isAlive());
        assertThrows(IOException.class, () -> SidecarProcess.start(() -> { throw new IOException("start failed"); },
                () -> true, () -> true, Duration.ofSeconds(3), clock::get, () -> fail("must not wait")));
        var ready = new FakeProcess();
        assertSame(ready, SidecarProcess.start(() -> ready, () -> true, () -> true,
                Duration.ofSeconds(3), clock::get, () -> fail("must not wait")));
        assertTrue(ready.isAlive()); ready.destroy();
    }

    @Test void persistedWireIdentityIsOptionalOpaqueAndReadyMarkerBelongsToSession() throws Exception {
        var store = new DedicatedRestoreStore(temporary);
        var session = new DedicatedRestoreSession(UUID.randomUUID(), "test", "world", temporary.resolve("world"), temporary,
                List.of("restart"), 1, 8, 60, DedicatedRestoreSession.State.PREPARED, Instant.now(), "");
        store.writeActive(session, "gui:request/not-a-uuid", "desktop");
        var read = new DedicatedRestoreStore(temporary);
        var loaded = read.readActive().orElseThrow();
        assertEquals("gui:request/not-a-uuid", read.signalRequestId(loaded));
        assertEquals("desktop", read.signalFrom());
        read.writeActive(loaded.withState(DedicatedRestoreSession.State.READY, ""));
        assertEquals("gui:request/not-a-uuid", store.signalRequestId(store.readActive().orElseThrow()));
        read.markReady(session.requestId());
        assertTrue(read.readyFor(session.requestId()));
        assertFalse(read.readyFor(UUID.randomUUID()));
        var legacy = new DedicatedRestoreStore(temporary.resolve("legacy"));
        legacy.writeActive(session);
        assertEquals(session.requestId().toString(), legacy.signalRequestId(legacy.readActive().orElseThrow()));
    }

    @Test void sidecarRejectsUnboundOrMalformedTerminalsAndAcceptsLegacyWorldSignal() {
        var tracker = new SidecarSignalTracker("opaque/id", "desktop", "world");
        tracker.accept(Map.of("event", "restore_finished", "world", "world", "status", "success"));
        assertTrue(tracker.terminal().isEmpty(), "A mutation terminal cannot precede world release");
        tracker.releaseRequested();
        tracker.accept(Map.of("event", "restore_finished", "status", "success"));
        tracker.accept(Map.of("event", "restore_finished", "world", "world", "status", "unknown"));
        tracker.accept(Map.of("event", "restore_finished", "world", "world", "request_id", "other", "status", "success"));
        tracker.accept(Map.of("event", "restore_finished", "world", "world", "from", "other", "status", "success"));
        assertTrue(tracker.terminal().isEmpty());
        tracker.accept(Map.of("event", "restore_finished", "world", "world", "status", "failed"));
        assertEquals(SidecarSignalTracker.Outcome.FAILURE, tracker.terminal().orElseThrow());
    }

    private static final class FakeProcess extends Process {
        private boolean alive = true;
        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { return 1; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { return !alive; }
        @Override public int exitValue() { if (alive) throw new IllegalThreadStateException(); return 1; }
        @Override public void destroy() { alive = false; }
        @Override public Process destroyForcibly() { destroy(); return this; }
        @Override public boolean isAlive() { return alive; }
    }
}
