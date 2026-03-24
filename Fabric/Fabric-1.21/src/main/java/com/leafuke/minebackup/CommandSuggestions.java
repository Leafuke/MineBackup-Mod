package com.leafuke.minebackup;

import com.leafuke.minebackup.knotlink.OpenSocketQuerier;
import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public final class CommandSuggestions {
    private static final String QUERIER_APP_ID = "0x00000020";
    private static final String QUERIER_SOCKET_ID = "0x00000010";
    private static final long CONFIG_QUERY_INTERVAL_MS = 5000L;
    private static final long CURRENT_BACKUPS_QUERY_INTERVAL_MS = 5000L;

    private static volatile long lastConfigsQueryAtMs = 0L;
    private static volatile List<ConfigDescriptor> lastConfigs = List.of();
    private static CompletableFuture<List<ConfigDescriptor>> configQueryFuture;

    private static volatile long lastCurrentBackupsQueryAtMs = 0L;
    private static volatile String lastCurrentBackupsResponse;
    private static CompletableFuture<String> currentBackupsQueryFuture;

    private CommandSuggestions() {
    }

    public static CompletableFuture<Suggestions> suggestConfigIds(SuggestionsBuilder builder) {
        return queryConfigsThrottled()
                .thenApply(configs -> {
                    String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
                    for (ConfigDescriptor config : configs) {
                        if (matchesConfig(config, remaining)) {
                            builder.suggest(config.id(), new LiteralMessage(config.name()));
                        }
                    }
                    return builder.build();
                })
                .exceptionally(ex -> {
                    MineBackup.LOGGER.warn("Failed to suggest config ids: {}", ex.getMessage());
                    return builder.build();
                });
    }

    public static CompletableFuture<Suggestions> suggestWorldIndices(String configId, SuggestionsBuilder builder) {
        return queryWorlds(configId)
                .thenApply(worlds -> {
                    String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
                    for (WorldDescriptor world : worlds) {
                        String indexText = String.valueOf(world.index());
                        String nameLower = world.name().toLowerCase(Locale.ROOT);
                        if (remaining.isEmpty()
                                || indexText.startsWith(remaining)
                                || nameLower.startsWith(remaining)
                                || nameLower.contains(remaining)) {
                            builder.suggest(indexText, new LiteralMessage(world.name()));
                        }
                    }
                    return builder.build();
                })
                .exceptionally(ex -> {
                    MineBackup.LOGGER.warn("Failed to suggest world indices: {}", ex.getMessage());
                    return builder.build();
                });
    }

    public static CompletableFuture<Suggestions> suggestBackupFiles(String configId, int worldIndex, SuggestionsBuilder builder) {
        String command = String.format("LIST_BACKUPS %s %d", configId, worldIndex);
        return queryBackend(command)
                .thenApply(response -> {
                    String remaining = normalizeQuotedInput(builder.getRemaining()).toLowerCase(Locale.ROOT);
                    if (response != null && response.startsWith("OK:")) {
                        for (String file : splitOkList(response)) {
                            String suggestion = quoteSuggestion(file);
                            if (suggestion != null && file.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                                builder.suggest(suggestion);
                            }
                        }
                    }
                    return builder.build();
                })
                .exceptionally(ex -> {
                    MineBackup.LOGGER.warn("Failed to suggest backup files: {}", ex.getMessage());
                    return builder.build();
                });
    }

    public static CompletableFuture<Suggestions> suggestCurrentBackupFiles(SuggestionsBuilder builder) {
        return queryCurrentBackupsThrottled()
                .thenApply(response -> {
                    String remaining = normalizeQuotedInput(builder.getRemaining()).toLowerCase(Locale.ROOT);
                    if (response != null && response.startsWith("OK:")) {
                        for (String file : splitOkList(response)) {
                            String suggestion = quoteSuggestion(file);
                            if (suggestion != null && file.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                                builder.suggest(suggestion);
                            }
                        }
                    }
                    return builder.build();
                })
                .exceptionally(ex -> {
                    MineBackup.LOGGER.warn("Failed to suggest current backup files: {}", ex.getMessage());
                    return builder.build();
                });
    }

    private static CompletableFuture<List<ConfigDescriptor>> queryConfigsThrottled() {
        synchronized (CommandSuggestions.class) {
            long now = System.currentTimeMillis();
            if (now - lastConfigsQueryAtMs < CONFIG_QUERY_INTERVAL_MS) {
                if (configQueryFuture != null && !configQueryFuture.isDone()) {
                    return configQueryFuture;
                }
                return CompletableFuture.completedFuture(lastConfigs);
            }

            lastConfigsQueryAtMs = now;
            CompletableFuture<String> future = queryBackend("LIST_CONFIGS");
            configQueryFuture = future.handle((response, ex) -> {
                synchronized (CommandSuggestions.class) {
                    configQueryFuture = null;
                    if (ex == null && response != null && response.startsWith("OK:")) {
                        lastConfigs = parseConfigs(response);
                    }
                }
                if (ex != null) {
                    MineBackup.LOGGER.warn("Failed to query configs: {}", ex.getMessage());
                }
                return lastConfigs;
            });
            return configQueryFuture;
        }
    }

    private static CompletableFuture<List<WorldDescriptor>> queryWorlds(String configId) {
        String normalized = normalizeConfigId(configId);
        if (normalized == null) {
            return CompletableFuture.completedFuture(List.of());
        }

        return queryBackend(String.format("LIST_WORLDS %s", normalized))
                .thenApply(CommandSuggestions::parseWorlds);
    }

    private static CompletableFuture<String> queryCurrentBackupsThrottled() {
        synchronized (CommandSuggestions.class) {
            long now = System.currentTimeMillis();
            if (now - lastCurrentBackupsQueryAtMs < CURRENT_BACKUPS_QUERY_INTERVAL_MS) {
                if (currentBackupsQueryFuture != null && !currentBackupsQueryFuture.isDone()) {
                    return currentBackupsQueryFuture;
                }
                return CompletableFuture.completedFuture(lastCurrentBackupsResponse);
            }

            lastCurrentBackupsQueryAtMs = now;
            CompletableFuture<String> future = queryBackend("LIST_BACKUPS_CURRENT");
            currentBackupsQueryFuture = future.handle((response, ex) -> {
                synchronized (CommandSuggestions.class) {
                    currentBackupsQueryFuture = null;
                    if (ex == null && response != null && response.startsWith("OK:")) {
                        lastCurrentBackupsResponse = response;
                    }
                }
                if (ex != null) {
                    MineBackup.LOGGER.warn("Failed to query current backups: {}", ex.getMessage());
                    return lastCurrentBackupsResponse;
                }
                return response;
            });
            return currentBackupsQueryFuture;
        }
    }

    private static CompletableFuture<String> queryBackend(String command) {
        CompletableFuture<String> future = OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, command);
        if (future == null) {
            return CompletableFuture.completedFuture(null);
        }
        return future;
    }

    private static List<ConfigDescriptor> parseConfigs(String response) {
        List<ConfigDescriptor> configs = new ArrayList<>();
        for (String item : splitOkList(response)) {
            String[] parts = item.split(",", 2);
            if (parts.length == 2) {
                String id = normalizeConfigId(parts[0]);
                String name = parts[1].trim();
                if (id != null && !name.isEmpty()) {
                    configs.add(new ConfigDescriptor(id, name));
                }
            }
        }
        return configs;
    }

    private static List<WorldDescriptor> parseWorlds(String response) {
        List<WorldDescriptor> worlds = new ArrayList<>();
        if (response == null || !response.startsWith("OK:")) {
            return worlds;
        }

        String data = response.substring(3);
        if (data.isEmpty()) {
            return worlds;
        }

        String[] parts = data.split(";");
        for (int i = 0; i < parts.length; i++) {
            String name = parts[i].trim();
            if (!name.isEmpty()) {
                worlds.add(new WorldDescriptor(i, name));
            }
        }
        return worlds;
    }

    private static List<String> splitOkList(String response) {
        if (response == null || !response.startsWith("OK:")) {
            return List.of();
        }

        String data = response.substring(3);
        if (data.isEmpty()) {
            return List.of();
        }

        List<String> values = new ArrayList<>();
        for (String item : data.split(";")) {
            if (!item.isEmpty()) {
                values.add(item);
            }
        }
        return values;
    }

    private static boolean matchesConfig(ConfigDescriptor config, String remaining) {
        if (remaining.isEmpty()) {
            return true;
        }
        String idLower = config.id().toLowerCase(Locale.ROOT);
        String nameLower = config.name().toLowerCase(Locale.ROOT);
        return idLower.startsWith(remaining) || nameLower.startsWith(remaining) || nameLower.contains(remaining);
    }

    private static String normalizeQuotedInput(String remaining) {
        if (remaining == null || remaining.isEmpty()) {
            return "";
        }
        return remaining.charAt(0) == '\'' ? remaining.substring(1) : remaining;
    }

    private static String quoteSuggestion(String value) {
        if (value.indexOf('\'') >= 0) {
            return null;
        }
        return "'" + value + "'";
    }

    private static String normalizeConfigId(String rawConfigId) {
        if (rawConfigId == null) {
            return null;
        }
        String trimmed = rawConfigId.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record ConfigDescriptor(String id, String name) {
    }

    private record WorldDescriptor(int index, String name) {
    }
}
