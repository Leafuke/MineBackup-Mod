package com.leafuke.minebackup.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldReleaseProbeTest {
    @TempDir
    Path root;

    @Test
    void doesNotReleaseWhileSessionLockIsHeld() throws Exception {
        Path lockPath = Files.writeString(root.resolve("session.lock"), "lock");
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            assertFalse(WorldReleaseProbe.isReleased(root));
        }
        assertTrue(WorldReleaseProbe.isReleased(root));
    }
}
