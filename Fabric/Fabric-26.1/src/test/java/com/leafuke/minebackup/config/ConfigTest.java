package com.leafuke.minebackup.config;

import org.junit.jupiter.api.Test;

import java.util.Properties;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigTest {
    @TempDir
    Path root;
    @Test
    void restoreCountdownUsesDefaultAndClampsNumericValues() {
        Config.Snapshot defaults = Config.fromProperties(new Properties());
        assertEquals(10, defaults.restore().countdownSeconds());

        Properties tooLarge = new Properties();
        tooLarge.setProperty("restore.countdownSeconds", "999");
        assertEquals(
                Config.MAX_RESTORE_COUNTDOWN_SECONDS,
                Config.fromProperties(tooLarge).restore().countdownSeconds());

        Properties invalid = new Properties();
        invalid.setProperty("restore.countdownSeconds", "not-a-number");
        assertEquals(10, Config.fromProperties(invalid).restore().countdownSeconds());
    }

    @Test
    void instanceAutomaticBackupKeyIsRetainedUntilWorldMigration() {
        Properties properties = new Properties();
        properties.setProperty("auto.currentWorld.intervalMinutes", "30");

        Config.Snapshot snapshot = Config.fromProperties(properties);

        assertEquals(30, snapshot.autoBackup().intervalMinutes());
        Properties canonical = Config.toProperties(snapshot);
        assertEquals("30", canonical.getProperty("auto.currentWorld.intervalMinutes"));
        assertFalse(canonical.containsKey("auto.configId"));
        assertFalse(canonical.containsKey("auto.folder"));
    }

    @Test
    void legacyTargetScheduleIsDisabledAndReported() {
        Config.consumeLegacyAutoBackupMigrationNotice();
        Properties legacy = new Properties();
        legacy.setProperty("auto.configId", "legacy-config");
        legacy.setProperty("auto.folder", "world");
        legacy.setProperty("auto.intervalMinutes", "15");

        Config.Snapshot snapshot = Config.fromProperties(legacy);

        assertNull(snapshot.autoBackup());
        assertTrue(Config.consumeLegacyAutoBackupMigrationNotice());
        assertFalse(Config.consumeLegacyAutoBackupMigrationNotice());
        Properties canonical = Config.toProperties(snapshot);
        assertFalse(canonical.containsKey("auto.configId"));
        assertFalse(canonical.containsKey("auto.folder"));
        assertFalse(canonical.containsKey("auto.intervalMinutes"));
    }

    @Test
    void dedicatedRestoreDefaultsAndParsesTimeouts() {
        Config.DedicatedRestore defaults = Config.fromProperties(new Properties()).dedicatedRestore();
        assertEquals(Config.DedicatedRestoreMode.SIDECAR, defaults.mode());
        assertEquals(5, defaults.sidecarStartTimeoutSeconds());
        assertEquals(8, defaults.worldReleaseTimeoutSeconds());
        assertEquals(3600, defaults.operationTimeoutSeconds());

        Properties values = new Properties();
        values.setProperty("dedicatedRestore.mode", "disabled");
        values.setProperty("dedicatedRestore.restartScript", " run.cmd ");
        values.setProperty("dedicatedRestore.operationTimeoutSeconds", "120");
        Config.DedicatedRestore parsed = Config.fromProperties(values).dedicatedRestore();
        assertEquals(Config.DedicatedRestoreMode.DISABLED, parsed.mode());
        assertEquals("run.cmd", parsed.restartScript());
        assertEquals(120, parsed.operationTimeoutSeconds());
    }

    @Test
    void migratesLegacyFileOnlyWhenCanonicalDoesNotExist() throws Exception {
        Path target = root.resolve("minebackup.properties");
        Path legacy = root.resolve("minebackup-auto.properties");
        Files.writeString(legacy, "restore.countdownSeconds=5");
        assertTrue(Config.migrateLegacyConfig(target));
        assertTrue(Files.isRegularFile(target));
        assertFalse(Files.exists(legacy));
        Files.writeString(legacy, "restore.countdownSeconds=7");
        assertFalse(Config.migrateLegacyConfig(target));
        assertTrue(Files.exists(legacy));
    }
}
