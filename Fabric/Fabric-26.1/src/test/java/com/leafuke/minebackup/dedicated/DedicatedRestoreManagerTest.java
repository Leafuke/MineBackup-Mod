package com.leafuke.minebackup.dedicated;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DedicatedRestoreManagerTest {
    @Test
    void sidecarUsesMineBackupCodeSourceInsteadOfLauncherClasspath() {
        String original = System.getProperty("java.class.path");
        try {
            System.setProperty("java.class.path", "launcher-only-sentinel.jar");
            String classpath = DedicatedRestoreManager.sidecarClasspath();
            assertFalse(classpath.contains("launcher-only-sentinel.jar"));

            var command = DedicatedRestoreManager.sidecarCommand(Path.of("restart dir"));
            assertEquals("-cp", command.get(1));
            assertEquals(classpath, command.get(2));
            assertEquals(DedicatedRestoreSidecar.class.getName(), command.get(3));
            assertTrue(classpath.contains(java.io.File.pathSeparator)
                    || Files.exists(Path.of(classpath)));
        } finally {
            if (original == null) {
                System.clearProperty("java.class.path");
            } else {
                System.setProperty("java.class.path", original);
            }
        }
    }
}
