package com.leafuke.minebackup;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

public final class Config {
    private static final String CONFIG_FILE = "minebackup-auto.properties";

    private static final boolean HOST_REOPEN_ENABLED_DEFAULT = true;
    private static final int HOST_REOPEN_RETRY_COUNT_DEFAULT = 6;
    private static final int HOST_REOPEN_RETRY_INTERVAL_TICKS_DEFAULT = 40;
    private static final boolean HOST_REOPEN_RANDOM_FALLBACK_DEFAULT = true;
    private static final boolean CLIENT_RECONNECT_ENABLED_DEFAULT = true;
    private static final int CLIENT_RECONNECT_INITIAL_DELAY_TICKS_DEFAULT = 200;
    private static final int CLIENT_RECONNECT_RETRY_INTERVAL_TICKS_DEFAULT = 100;
    private static final int CLIENT_RECONNECT_MAX_DURATION_TICKS_DEFAULT = 1800;
    private static final boolean UPDATE_CHECK_ENABLED_DEFAULT = true;
    public static final int MAX_AUTO_BACKUP_INTERVAL_MINUTES = 525_600;

    private static final AtomicReference<Snapshot> CURRENT = new AtomicReference<>(defaults());

    private Config() {
    }

    public static synchronized Snapshot load() {
        Path configPath = configPath();
        if (!Files.exists(configPath)) {
            Snapshot defaults = defaults();
            CURRENT.set(defaults);
            write(defaults);
            return defaults;
        }

        Properties source = new Properties();
        try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            source.load(reader);
            Snapshot snapshot = fromProperties(source);
            CURRENT.set(snapshot);
            if (!sameKnownValues(source, toProperties(snapshot))) {
                write(snapshot);
            }
            return snapshot;
        } catch (IOException exception) {
            MineBackup.LOGGER.error("Failed to load MineBackup config; using defaults for this session", exception);
            Snapshot defaults = defaults();
            CURRENT.set(defaults);
            return defaults;
        }
    }

    public static Snapshot get() {
        return CURRENT.get();
    }

    public static synchronized void setAutoBackup(String configId, String folder, int intervalMinutes) {
        String normalizedConfigId = normalize(configId);
        String normalizedFolder = normalize(folder);
        if (normalizedConfigId == null
                || normalizedFolder == null
                || intervalMinutes < 1
                || intervalMinutes > MAX_AUTO_BACKUP_INTERVAL_MINUTES) {
            throw new IllegalArgumentException("Invalid automatic backup settings");
        }

        Snapshot updated = CURRENT.get().withAutoBackup(
                new AutoBackup(normalizedConfigId, normalizedFolder, intervalMinutes));
        CURRENT.set(updated);
        write(updated);
    }

    public static synchronized void clearAutoBackup() {
        Snapshot updated = CURRENT.get().withAutoBackup(null);
        CURRENT.set(updated);
        write(updated);
    }

    public static synchronized void clearAutoBackupIfMatches(String configId, String folder) {
        AutoBackup current = CURRENT.get().autoBackup();
        String normalizedConfigId = normalize(configId);
        String normalizedFolder = normalize(folder);
        if (current == null
                || !current.configId().equals(normalizedConfigId)
                || !current.folder().equals(normalizedFolder)) {
            return;
        }
        clearAutoBackup();
    }

    private static Snapshot fromProperties(Properties properties) {
        String configId = first(properties, "auto.configId", "configId");
        String folder = first(properties, "auto.folder", "worldIndex");
        int intervalMinutes = parseInt(
                first(properties, "auto.intervalMinutes", "internalTime"),
                -1,
                -1,
                MAX_AUTO_BACKUP_INTERVAL_MINUTES);
        AutoBackup autoBackup = normalize(configId) != null
                && normalize(folder) != null
                && intervalMinutes >= 1
                ? new AutoBackup(normalize(configId), normalize(folder), intervalMinutes)
                : null;

        HostReopen hostReopen = new HostReopen(
                parseBoolean(first(properties, "lan.hostReopen.enabled", "autoReopenLanAfterRestore"),
                        HOST_REOPEN_ENABLED_DEFAULT),
                parseInt(first(properties, "lan.hostReopen.retryCount", "lanReopenRetryCount"),
                        HOST_REOPEN_RETRY_COUNT_DEFAULT, 1, 30),
                parseInt(first(properties, "lan.hostReopen.retryIntervalTicks", "lanReopenRetryIntervalTicks"),
                        HOST_REOPEN_RETRY_INTERVAL_TICKS_DEFAULT, 10, 200),
                parseBoolean(first(properties, "lan.hostReopen.allowRandomPortFallback",
                                "lanReopenAllowRandomPortFallback"),
                        HOST_REOPEN_RANDOM_FALLBACK_DEFAULT));

        ClientReconnect clientReconnect = new ClientReconnect(
                parseBoolean(first(properties, "lan.clientReconnect.enabled",
                                "autoReconnectLanClientAfterRestore"),
                        CLIENT_RECONNECT_ENABLED_DEFAULT),
                parseInt(first(properties, "lan.clientReconnect.initialDelayTicks",
                                "lanClientReconnectInitialDelayTicks"),
                        CLIENT_RECONNECT_INITIAL_DELAY_TICKS_DEFAULT, 40, 600),
                parseInt(first(properties, "lan.clientReconnect.retryIntervalTicks",
                                "lanClientReconnectIntervalTicks"),
                        CLIENT_RECONNECT_RETRY_INTERVAL_TICKS_DEFAULT, 20, 200),
                parseInt(first(properties, "lan.clientReconnect.maxDurationTicks",
                                "lanClientReconnectMaxDurationTicks"),
                        CLIENT_RECONNECT_MAX_DURATION_TICKS_DEFAULT, 200, 7200));

        boolean updateCheckEnabled = parseBoolean(
                first(properties, "updateCheck.enabled", "enableUpdateCheck"),
                UPDATE_CHECK_ENABLED_DEFAULT);
        return new Snapshot(autoBackup, hostReopen, clientReconnect, updateCheckEnabled);
    }

    private static Properties toProperties(Snapshot snapshot) {
        Properties properties = new Properties();
        AutoBackup autoBackup = snapshot.autoBackup();
        if (autoBackup != null) {
            properties.setProperty("auto.configId", autoBackup.configId());
            properties.setProperty("auto.folder", autoBackup.folder());
            properties.setProperty("auto.intervalMinutes", String.valueOf(autoBackup.intervalMinutes()));
        }

        HostReopen host = snapshot.hostReopen();
        properties.setProperty("lan.hostReopen.enabled", String.valueOf(host.enabled()));
        properties.setProperty("lan.hostReopen.retryCount", String.valueOf(host.retryCount()));
        properties.setProperty("lan.hostReopen.retryIntervalTicks", String.valueOf(host.retryIntervalTicks()));
        properties.setProperty("lan.hostReopen.allowRandomPortFallback",
                String.valueOf(host.allowRandomPortFallback()));

        ClientReconnect client = snapshot.clientReconnect();
        properties.setProperty("lan.clientReconnect.enabled", String.valueOf(client.enabled()));
        properties.setProperty("lan.clientReconnect.initialDelayTicks", String.valueOf(client.initialDelayTicks()));
        properties.setProperty("lan.clientReconnect.retryIntervalTicks", String.valueOf(client.retryIntervalTicks()));
        properties.setProperty("lan.clientReconnect.maxDurationTicks", String.valueOf(client.maxDurationTicks()));
        properties.setProperty("updateCheck.enabled", String.valueOf(snapshot.updateCheckEnabled()));
        return properties;
    }

    private static boolean sameKnownValues(Properties source, Properties canonical) {
        if (source.size() != canonical.size()) {
            return false;
        }
        for (String key : canonical.stringPropertyNames()) {
            if (!Objects.equals(source.getProperty(key), canonical.getProperty(key))) {
                return false;
            }
        }
        return true;
    }

    private static void write(Snapshot snapshot) {
        Path target = configPath();
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                toProperties(snapshot).store(writer, "MineBackup 3 configuration");
            }
            try {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            MineBackup.LOGGER.error("Failed to save MineBackup config", exception);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException exception) {
                MineBackup.LOGGER.debug("Failed to remove temporary MineBackup config", exception);
            }
        }
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE);
    }

    private static Snapshot defaults() {
        return new Snapshot(
                null,
                new HostReopen(
                        HOST_REOPEN_ENABLED_DEFAULT,
                        HOST_REOPEN_RETRY_COUNT_DEFAULT,
                        HOST_REOPEN_RETRY_INTERVAL_TICKS_DEFAULT,
                        HOST_REOPEN_RANDOM_FALLBACK_DEFAULT),
                new ClientReconnect(
                        CLIENT_RECONNECT_ENABLED_DEFAULT,
                        CLIENT_RECONNECT_INITIAL_DELAY_TICKS_DEFAULT,
                        CLIENT_RECONNECT_RETRY_INTERVAL_TICKS_DEFAULT,
                        CLIENT_RECONNECT_MAX_DURATION_TICKS_DEFAULT),
                UPDATE_CHECK_ENABLED_DEFAULT);
    }

    private static String first(Properties properties, String canonicalKey, String legacyKey) {
        String canonical = properties.getProperty(canonicalKey);
        return canonical != null ? canonical : properties.getProperty(legacyKey);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static int parseInt(String value, int fallback, int minimum, int maximum) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Math.clamp(Integer.parseInt(value.trim()), minimum, maximum);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static boolean parseBoolean(String value, boolean fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> fallback;
        };
    }

    public record Snapshot(
            AutoBackup autoBackup,
            HostReopen hostReopen,
            ClientReconnect clientReconnect,
            boolean updateCheckEnabled) {
        public Snapshot {
            Objects.requireNonNull(hostReopen, "hostReopen");
            Objects.requireNonNull(clientReconnect, "clientReconnect");
        }

        public Snapshot withAutoBackup(AutoBackup value) {
            return new Snapshot(value, hostReopen, clientReconnect, updateCheckEnabled);
        }
    }

    public record AutoBackup(String configId, String folder, int intervalMinutes) {
        public AutoBackup {
            Objects.requireNonNull(configId, "configId");
            Objects.requireNonNull(folder, "folder");
            if (intervalMinutes < 1 || intervalMinutes > MAX_AUTO_BACKUP_INTERVAL_MINUTES) {
                throw new IllegalArgumentException("Automatic backup interval is outside the supported range");
            }
        }
    }

    public record HostReopen(
            boolean enabled,
            int retryCount,
            int retryIntervalTicks,
            boolean allowRandomPortFallback) {
    }

    public record ClientReconnect(
            boolean enabled,
            int initialDelayTicks,
            int retryIntervalTicks,
            int maxDurationTicks) {
    }
}
