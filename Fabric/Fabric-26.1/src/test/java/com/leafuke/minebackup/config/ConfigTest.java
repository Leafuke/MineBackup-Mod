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
    void oldAndMalformedConfigsKeepAlwaysShowingJoinStatus() {
        Config.Snapshot old = Config.fromProperties(new Properties());
        assertEquals(Config.JoinStatusNotificationMode.ALWAYS, old.joinStatusNotificationMode());
        assertEquals("ALWAYS", Config.toProperties(old)
                .getProperty("automation.joinStatusNotification.mode"));

        for (String invalid : new String[] {"", "  ", "true", "disabled", "unknown"}) {
            Properties properties = new Properties();
            properties.setProperty("automation.joinStatusNotification.mode", invalid);
            assertEquals(Config.JoinStatusNotificationMode.ALWAYS,
                    Config.fromProperties(properties).joinStatusNotificationMode(), invalid);
        }
    }

    @Test
    void joinStatusModesRoundTripAndSurviveScheduleMigration() {
        for (Config.JoinStatusNotificationMode mode : Config.JoinStatusNotificationMode.values()) {
            Properties properties = new Properties();
            properties.setProperty("automation.joinStatusNotification.mode",
                    "  " + mode.name().toLowerCase(java.util.Locale.ROOT) + "  ");
            properties.setProperty("auto.currentWorld.intervalMinutes", "30");
            properties.setProperty("restore.countdownSeconds", "17");

            Config.Snapshot parsed = Config.fromProperties(properties);
            assertEquals(mode, parsed.joinStatusNotificationMode());
            assertEquals(mode.name(), Config.toProperties(parsed)
                    .getProperty("automation.joinStatusNotification.mode"));
            assertEquals(parsed, Config.fromProperties(Config.toProperties(parsed)));

            Config.Snapshot migrated = parsed.withAutoBackup(null);
            assertNull(migrated.autoBackup());
            assertEquals(mode, migrated.joinStatusNotificationMode());
            assertEquals(17, migrated.restore().countdownSeconds());
            assertEquals(migrated, Config.fromProperties(Config.toProperties(migrated)));
            assertEquals(mode, migrated.withAutoBackup(new Config.AutoBackup(45))
                    .joinStatusNotificationMode());
        }
    }

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
        legacy.setProperty("automation.joinStatusNotification.mode", "never");

        Config.Snapshot snapshot = Config.fromProperties(legacy);

        assertNull(snapshot.autoBackup());
        assertTrue(Config.consumeLegacyAutoBackupMigrationNotice());
        assertFalse(Config.consumeLegacyAutoBackupMigrationNotice());
        Properties canonical = Config.toProperties(snapshot);
        assertFalse(canonical.containsKey("auto.configId"));
        assertFalse(canonical.containsKey("auto.folder"));
        assertFalse(canonical.containsKey("auto.intervalMinutes"));
        assertEquals("NEVER", canonical.getProperty("automation.joinStatusNotification.mode"));
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
        Files.writeString(legacy,
                "restore.countdownSeconds=5\nautomation.joinStatusNotification.mode=enabled_only\n");
        assertTrue(Config.migrateLegacyConfig(target));
        assertTrue(Files.isRegularFile(target));
        assertFalse(Files.exists(legacy));
        Properties migrated = new Properties();
        try (var reader = Files.newBufferedReader(target)) {
            migrated.load(reader);
        }
        assertEquals(Config.JoinStatusNotificationMode.ENABLED_ONLY,
                Config.fromProperties(migrated).joinStatusNotificationMode());
        Files.writeString(legacy, "restore.countdownSeconds=7");
        assertFalse(Config.migrateLegacyConfig(target));
        assertTrue(Files.exists(legacy));
    }
}
