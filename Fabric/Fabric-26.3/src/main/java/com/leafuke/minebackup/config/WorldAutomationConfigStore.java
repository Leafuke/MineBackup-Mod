package com.leafuke.minebackup.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.function.BooleanSupplier;

public final class WorldAutomationConfigStore {
    private static final Logger LOGGER = LoggerFactory.getLogger("minebackup");

    private final Path directory;

    public WorldAutomationConfigStore(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    public LoadResult load(WorldIdentity world) {
        Path path = pathFor(world);
        if (!Files.isRegularFile(path)) {
            return new LoadResult(Settings.off(), true);
        }

        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException exception) {
            LOGGER.error("Failed to load automation config for {}", world.displayName(), exception);
            return new LoadResult(Settings.off(), false);
        }

        if (!world.value().equals(properties.getProperty("world.identity"))) {
            LOGGER.error("Rejected mismatched automation config for {}", world.displayName());
            return new LoadResult(Settings.off(), false);
        }

        Mode mode = Mode.parse(properties.getProperty("automation.mode"));
        if (mode == null) {
            LOGGER.error("Disabled invalid automation mode for {}", world.displayName());
            return new LoadResult(Settings.off(), false);
        }
        if (mode == Mode.OFF) {
            return new LoadResult(Settings.off(), true);
        }
        int interval = parseInterval(properties.getProperty("automation.intervalMinutes"));
        if (interval < 1) {
            LOGGER.error("Disabled invalid automation interval for {}", world.displayName());
            return new LoadResult(Settings.off(), false);
        }
        return new LoadResult(new Settings(mode, interval), true);
    }

    public boolean write(WorldIdentity world, Settings settings) {
        Path target = pathFor(world);
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Properties properties = new Properties();
        properties.setProperty("world.identity", world.value());
        properties.setProperty("world.displayName", world.displayName());
        properties.setProperty("automation.mode", settings.mode().name());
        if (settings.active()) {
            properties.setProperty(
                    "automation.intervalMinutes",
                    String.valueOf(settings.intervalMinutes()));
        }

        try {
            Files.createDirectories(directory);
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                properties.store(writer, "MineBackup current-world automation");
            }
            moveReplacing(temporary, target);
            return true;
        } catch (IOException exception) {
            LOGGER.error("Failed to save automation config for {}", world.displayName(), exception);
            return false;
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException exception) {
                LOGGER.debug("Failed to remove temporary automation config", exception);
            }
        }
    }

    public MigrationResult migrateLegacyBackup(
            WorldIdentity world,
            Settings current,
            Integer legacyIntervalMinutes,
            BooleanSupplier clearGlobalSetting) {
        if (legacyIntervalMinutes == null) {
            return new MigrationResult(Migration.NONE, current);
        }

        Settings migrated = current;
        Migration success = Migration.EXISTING_WORLD_PLAN_PRESERVED;
        if (!current.active()) {
            migrated = Settings.backup(legacyIntervalMinutes);
            if (!write(world, migrated)) {
                return new MigrationResult(Migration.WORLD_WRITE_FAILED, Settings.off());
            }
            success = Migration.MIGRATED;
        }
        if (!clearGlobalSetting.getAsBoolean()) {
            return new MigrationResult(Migration.GLOBAL_CLEAR_FAILED, migrated);
        }
        return new MigrationResult(success, migrated);
    }

    public Path pathFor(WorldIdentity world) {
        return directory.resolve(world.storageKey() + ".properties");
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static int parseInterval(String value) {
        if (value == null || value.isBlank()) {
            return -1;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed >= 1 && parsed <= Config.MAX_AUTO_BACKUP_INTERVAL_MINUTES
                    ? parsed
                    : -1;
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    public enum Mode {
        OFF,
        BACKUP,
        REMIND;

        static Mode parse(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                return null;
            }
        }
    }

    public record Settings(Mode mode, int intervalMinutes) {
        public Settings {
            Objects.requireNonNull(mode, "mode");
            if (mode == Mode.OFF && intervalMinutes != 0) {
                throw new IllegalArgumentException("Disabled automation must not have an interval");
            }
            if (mode != Mode.OFF
                    && (intervalMinutes < 1
                    || intervalMinutes > Config.MAX_AUTO_BACKUP_INTERVAL_MINUTES)) {
                throw new IllegalArgumentException("Automation interval is outside the supported range");
            }
        }

        public static Settings off() {
            return new Settings(Mode.OFF, 0);
        }

        public static Settings backup(int intervalMinutes) {
            return new Settings(Mode.BACKUP, intervalMinutes);
        }

        public static Settings remind(int intervalMinutes) {
            return new Settings(Mode.REMIND, intervalMinutes);
        }

        public boolean active() {
            return mode != Mode.OFF;
        }
    }

    public record LoadResult(Settings settings, boolean valid) {
        public LoadResult {
            Objects.requireNonNull(settings, "settings");
        }
    }

    public enum Migration {
        NONE,
        MIGRATED,
        EXISTING_WORLD_PLAN_PRESERVED,
        WORLD_WRITE_FAILED,
        GLOBAL_CLEAR_FAILED,
        WORLD_LOAD_FAILED
    }

    public record MigrationResult(Migration migration, Settings settings) {
        public MigrationResult {
            Objects.requireNonNull(migration, "migration");
            Objects.requireNonNull(settings, "settings");
        }
    }
}
