package com.leafuke.minebackup.config;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigTest {
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
    void currentWorldAutomaticBackupUsesOnlyNewIntervalKey() {
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
}
