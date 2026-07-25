package com.leafuke.minebackup.dedicated;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DedicatedRestoreStoreTest {
    @TempDir
    Path root;

    @Test
    void roundTripsActiveSessionAndFinalResultAtomically() throws Exception {
        DedicatedRestoreStore store = new DedicatedRestoreStore(root.resolve("restart"));
        DedicatedRestoreSession session = new DedicatedRestoreSession(
                UUID.randomUUID(),
                "jea:incident",
                "current_save",
                root.resolve("world"),
                root,
                List.of("cmd.exe", "/c", root.resolve("start script.cmd").toString()),
                42L,
                8,
                3600,
                DedicatedRestoreSession.State.PREPARED,
                Instant.now(),
                "");
        store.writeActive(session);
        assertEquals(session.requestId(), store.readActive().orElseThrow().requestId());
        assertEquals(session.restartCommand(), store.readActive().orElseThrow().restartCommand());

        store.markReady();
        assertTrue(Files.isRegularFile(store.readyPath()));
        store.writeFinal(session.withState(DedicatedRestoreSession.State.RESTART_STARTED, ""));
        assertFalse(store.readActive().isPresent());
        assertFalse(Files.exists(store.readyPath()));
        assertEquals(
                DedicatedRestoreSession.State.RESTART_STARTED,
                store.readLastResult().orElseThrow().state());
    }
}
