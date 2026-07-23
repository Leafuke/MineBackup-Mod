package com.leafuke.minebackup.command;

import com.leafuke.minebackup.MineBackup;
import com.leafuke.minebackup.knotlink.protocol.KnotLinkRequest;
import com.leafuke.minebackup.knotlink.protocol.KnotLinkResponse;
import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public final class CommandSuggestions {
    private static final long CACHE_NANOS = java.time.Duration.ofSeconds(5).toNanos();
    private static final Object CONFIG_CACHE_LOCK = new Object();
    private static long configCacheAt;
    private static List<ConfigDescriptor> configCache = List.of();
    private static CompletableFuture<List<ConfigDescriptor>> configQuery;

    private CommandSuggestions() {
    }

    public static CompletableFuture<Suggestions> suggestConfigIds(SuggestionsBuilder builder) {
        return queryConfigs().handle((configs, error) -> {
            if (error != null) {
                MineBackup.LOGGER.debug("Unable to query config suggestions", error);
                return builder.build();
            }
            String remaining = normalizeRemaining(builder.getRemaining());
            for (ConfigDescriptor config : configs) {
                if (matches(config.id(), remaining) || matches(config.name(), remaining)) {
                    builder.suggest(config.id(), new LiteralMessage(config.name()));
                }
            }
            return builder.build();
        });
    }

    public static CompletableFuture<Suggestions> suggestFolders(String configId, SuggestionsBuilder builder) {
        KnotLinkRequest request = KnotLinkRequest.command("LIST_FOLDERS")
                .field("config_id", configId);
        return queryData(request).handle((data, error) -> {
            if (error != null) {
                MineBackup.LOGGER.debug("Unable to query folder suggestions", error);
                return builder.build();
            }
            String remaining = normalizeRemaining(builder.getRemaining());
            List<String> folders = splitList(data);
            for (int index = 0; index < folders.size(); index++) {
                String folder = folders.get(index);
                if (matches(folder, remaining) || String.valueOf(index).startsWith(remaining)) {
                    builder.suggest(
                            StringArgumentType.escapeIfRequired(folder),
                            new LiteralMessage("#" + index));
                }
            }
            return builder.build();
        });
    }

    public static CompletableFuture<Suggestions> suggestBackups(
            String configId,
            String folder,
            SuggestionsBuilder builder) {
        KnotLinkRequest request = KnotLinkRequest.command("LIST_BACKUPS")
                .field("config_id", configId)
                .field("folder", folder);
        return queryData(request).handle((data, error) -> {
            if (error != null) {
                MineBackup.LOGGER.debug("Unable to query backup suggestions", error);
                return builder.build();
            }
            String remaining = normalizeRemaining(builder.getRemaining());
            for (String backup : splitList(data)) {
                if (matches(backup, remaining)) {
                    builder.suggest(StringArgumentType.escapeIfRequired(backup));
                }
            }
            return builder.build();
        });
    }

    public static CompletableFuture<Suggestions> suggestCurrentBackups(SuggestionsBuilder builder) {
        KnotLinkRequest request = KnotLinkRequest.command("LIST_BACKUPS")
                .field("current_save", true);
        return queryData(request).handle((data, error) -> {
            if (error != null) {
                MineBackup.LOGGER.debug("Unable to query current backup suggestions", error);
                return builder.build();
            }
            String remaining = normalizeRemaining(builder.getRemaining());
            for (String backup : splitList(data)) {
                if (matches(backup, remaining)) {
                    builder.suggest(StringArgumentType.escapeIfRequired(backup));
                }
            }
            return builder.build();
        });
    }

    static List<String> splitList(String data) {
        if (data == null || data.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String item : data.split(";", -1)) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }
        return List.copyOf(values);
    }

    private static CompletableFuture<List<ConfigDescriptor>> queryConfigs() {
        synchronized (CONFIG_CACHE_LOCK) {
            long now = System.nanoTime();
            if (configQuery != null) {
                return configQuery;
            }
            if (now - configCacheAt < CACHE_NANOS) {
                return CompletableFuture.completedFuture(configCache);
            }

            configQuery = queryData(KnotLinkRequest.command("LIST_CONFIGS"))
                    .thenApply(CommandSuggestions::parseConfigs)
                    .whenComplete((configs, error) -> {
                        synchronized (CONFIG_CACHE_LOCK) {
                            if (error == null) {
                                configCache = configs;
                                configCacheAt = System.nanoTime();
                            }
                            configQuery = null;
                        }
                    });
            return configQuery;
        }
    }

    private static CompletableFuture<String> queryData(KnotLinkRequest request) {
        return MineBackup.knotLink().query(request).thenApply(response -> {
            if (!response.isOk()) {
                throw new IllegalStateException(response.displayMessage());
            }
            return response.data();
        });
    }

    private static List<ConfigDescriptor> parseConfigs(String data) {
        List<ConfigDescriptor> configs = new ArrayList<>();
        for (String item : splitList(data)) {
            String[] parts = item.split(",", 2);
            if (parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank()) {
                configs.add(new ConfigDescriptor(parts[0].trim(), parts[1].trim()));
            }
        }
        return List.copyOf(configs);
    }

    private static boolean matches(String candidate, String remaining) {
        if (remaining.isEmpty()) {
            return true;
        }
        return candidate.toLowerCase(Locale.ROOT).contains(remaining);
    }

    private static String normalizeRemaining(String remaining) {
        if (remaining == null || remaining.isBlank()) {
            return "";
        }
        String normalized = remaining.trim();
        if (normalized.startsWith("\"")) {
            normalized = normalized.substring(1);
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private record ConfigDescriptor(String id, String name) {
    }
}
