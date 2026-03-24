package com.leafuke.minebackup;

import com.leafuke.minebackup.knotlink.OpenSocketQuerier;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public class Command {
    private static final String QUERIER_APP_ID = "0x00000020";
    private static final String QUERIER_SOCKET_ID = "0x00000010";
    private static final long CURRENT_BACKUPS_QUERY_INTERVAL_MS = 5000L;
    private static volatile long lastCurrentBackupsQueryAtMs = 0L;
    private static volatile String lastCurrentBackupsResponse = null;
    private static CompletableFuture<String> currentBackupsQueryFuture = null;

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("mb")
                .requires(Command::hasCommandAccess)
                .then(CommandManager.literal("save")
                        .executes(ctx -> {
                            ServerCommandSource source = ctx.getSource();
                            if (handleDedicatedServerUnsupported(source)) {
                                return 1;
                            }
                            saveAllWorlds(source);
                            return 1;
                        })
                )
                .then(CommandManager.literal("list_configs")
                        .executes(ctx -> executeDedicatedAware(ctx.getSource(), () -> {
                            ctx.getSource().sendFeedback(() -> Text.translatable("minebackup.message.list_configs.start"), false);
                            queryBackend("LIST_CONFIGS", response -> handleListConfigsResponse(ctx.getSource(), response));
                        }))
                )
                .then(CommandManager.literal("list_worlds")
                        .then(CommandManager.argument("config_id", IntegerArgumentType.integer())
                                .executes(ctx -> executeDedicatedAware(ctx.getSource(), () -> {
                                    int configId = IntegerArgumentType.getInteger(ctx, "config_id");
                                    ctx.getSource().sendFeedback(() -> Text.translatable("minebackup.message.list_worlds.start", String.valueOf(configId)), false);
                                    queryBackend(
                                            String.format("LIST_WORLDS %d", configId),
                                            response -> handleListWorldsResponse(ctx.getSource(), response, configId)
                                    );
                                }))
                        )
                )
                .then(CommandManager.literal("list_backups")
                        .then(CommandManager.argument("config_id", IntegerArgumentType.integer())
                                .then(CommandManager.argument("world_index", IntegerArgumentType.integer())
                                        .executes(ctx -> executeDedicatedAware(ctx.getSource(), () -> {
                                            int configId = IntegerArgumentType.getInteger(ctx, "config_id");
                                            int worldIndex = IntegerArgumentType.getInteger(ctx, "world_index");
                                            ctx.getSource().sendFeedback(() -> Text.translatable("minebackup.message.list_backups.start", String.valueOf(configId), String.valueOf(worldIndex)), false);
                                            queryBackend(
                                                    String.format("LIST_BACKUPS %d %d", configId, worldIndex),
                                                    response -> handleListBackupsResponse(ctx.getSource(), response, configId, worldIndex)
                                            );
                                        }))
                                )
                        )
                )
                .then(CommandManager.literal("backup")
                        .then(CommandManager.argument("config_id", IntegerArgumentType.integer())
                                .then(CommandManager.argument("world_index", IntegerArgumentType.integer())
                                        .executes(ctx -> executeRemoteCommand(ctx.getSource(),
                                                String.format("BACKUP %d %d",
                                                        IntegerArgumentType.getInteger(ctx, "config_id"),
                                                        IntegerArgumentType.getInteger(ctx, "world_index"))))
                                        .then(CommandManager.argument("comment", StringArgumentType.greedyString())
                                                .executes(ctx -> executeRemoteCommand(ctx.getSource(),
                                                        String.format("BACKUP %d %d %s",
                                                                IntegerArgumentType.getInteger(ctx, "config_id"),
                                                                IntegerArgumentType.getInteger(ctx, "world_index"),
                                                                StringArgumentType.getString(ctx, "comment"))))
                                        )
                                )
                        )
                )
                .then(CommandManager.literal("restore")
                        .then(CommandManager.argument("config_id", IntegerArgumentType.integer())
                                .then(CommandManager.argument("world_index", IntegerArgumentType.integer())
                                        .then(CommandManager.argument("backup_file", StringArgumentType.string())
                                                .suggests((ctx, builder) -> suggestBackupFiles(
                                                        IntegerArgumentType.getInteger(ctx, "config_id"),
                                                        IntegerArgumentType.getInteger(ctx, "world_index"),
                                                        builder))
                                                .executes(ctx -> {
                                                    String backupFile = requireSingleQuotedString(ctx, "backup_file");
                                                    if (backupFile == null) {
                                                        return 0;
                                                    }
                                                    return executeRemoteCommand(ctx.getSource(),
                                                            String.format("RESTORE %d %d %s",
                                                                    IntegerArgumentType.getInteger(ctx, "config_id"),
                                                                    IntegerArgumentType.getInteger(ctx, "world_index"),
                                                                    backupFile));
                                                })
                                        )
                                )
                        )
                )
                .then(CommandManager.literal("quicksave")
                        .executes(ctx -> executeRemoteCommand(ctx.getSource(), "BACKUP_CURRENT"))
                        .then(CommandManager.argument("comment", StringArgumentType.greedyString())
                                .executes(ctx -> executeRemoteCommand(ctx.getSource(),
                                        String.format("BACKUP_CURRENT %s", StringArgumentType.getString(ctx, "comment"))))
                        )
                )
                .then(CommandManager.literal("quickrestore")
                        .executes(ctx -> executeRemoteCommand(ctx.getSource(), "RESTORE_CURRENT_LATEST"))
                        .then(CommandManager.argument("backup_file", StringArgumentType.string())
                                .suggests((ctx, builder) -> suggestCurrentBackupFiles(builder))
                                .executes(ctx -> {
                                    String backupFile = requireSingleQuotedString(ctx, "backup_file");
                                    if (backupFile == null) {
                                        return 0;
                                    }
                                    return executeRemoteCommand(ctx.getSource(),
                                            String.format("RESTORE_CURRENT %s", backupFile));
                                })
                        )
                )
                .then(CommandManager.literal("auto")
                        .then(CommandManager.argument("config_id", IntegerArgumentType.integer())
                                .then(CommandManager.argument("world_index", IntegerArgumentType.integer())
                                        .then(CommandManager.argument("internal_time", IntegerArgumentType.integer())
                                                .executes(ctx -> {
                                                    Config.setAutoBackup(
                                                            IntegerArgumentType.getInteger(ctx, "config_id"),
                                                            IntegerArgumentType.getInteger(ctx, "world_index"),
                                                            IntegerArgumentType.getInteger(ctx, "internal_time")
                                                    );
                                                    return executeRemoteCommand(ctx.getSource(),
                                                            String.format("AUTO_BACKUP %d %d %d",
                                                                    IntegerArgumentType.getInteger(ctx, "config_id"),
                                                                    IntegerArgumentType.getInteger(ctx, "world_index"),
                                                                    IntegerArgumentType.getInteger(ctx, "internal_time")));
                                                })
                                        )
                                )
                        )
                )
                .then(CommandManager.literal("stop")
                        .then(CommandManager.argument("config_id", IntegerArgumentType.integer())
                                .then(CommandManager.argument("world_index", IntegerArgumentType.integer())
                                        .executes(ctx -> {
                                            Config.clearAutoBackup();
                                            return executeRemoteCommand(ctx.getSource(),
                                                    String.format("STOP_AUTO_BACKUP %d %d",
                                                            IntegerArgumentType.getInteger(ctx, "config_id"),
                                                            IntegerArgumentType.getInteger(ctx, "world_index")));
                                        })
                                )
                        )
                )
                .then(CommandManager.literal("snap")
                        .then(CommandManager.argument("config_id", IntegerArgumentType.integer())
                                .then(CommandManager.argument("world_index", IntegerArgumentType.integer())
                                        .then(CommandManager.argument("backup_file", StringArgumentType.string())
                                                .suggests((ctx, builder) -> suggestBackupFiles(
                                                        IntegerArgumentType.getInteger(ctx, "config_id"),
                                                        IntegerArgumentType.getInteger(ctx, "world_index"),
                                                        builder))
                                                .executes(ctx -> {
                                                    String backupFile = requireSingleQuotedString(ctx, "backup_file");
                                                    if (backupFile == null) {
                                                        return 0;
                                                    }
                                                    String command = String.format("ADD_TO_WE %d %d %s",
                                                            IntegerArgumentType.getInteger(ctx, "config_id"),
                                                            IntegerArgumentType.getInteger(ctx, "world_index"),
                                                            backupFile);
                                                    ctx.getSource().sendFeedback(() -> Text.translatable("minebackup.message.snap.sent", command), false);
                                                    return executeDedicatedAware(ctx.getSource(),
                                                            () -> queryBackend(command, response -> handleGenericResponse(ctx.getSource(), response, "snap")));
                                                })
                                        )
                                )
                        )
                )
                .then(CommandManager.literal("freeze")
                        .executes(ctx -> {
                            ServerCommandSource source = ctx.getSource();
                            if (handleDedicatedServerUnsupported(source)) {
                                return 1;
                            }
                            MinecraftServer server = source.getServer();
                            if (MineBackup.isSaveFrozen()) {
                                source.sendError(Text.translatable("minebackup.message.freeze.already"));
                                return 0;
                            }
                            saveAllWorlds(source);
                            MineBackup.freezeAutoSave(server);
                            source.sendFeedback(() -> Text.translatable("minebackup.message.freeze.success"), true);
                            return 1;
                        })
                )
                .then(CommandManager.literal("unfreeze")
                        .executes(ctx -> {
                            ServerCommandSource source = ctx.getSource();
                            if (handleDedicatedServerUnsupported(source)) {
                                return 1;
                            }
                            MinecraftServer server = source.getServer();
                            if (!MineBackup.isSaveFrozen()) {
                                source.sendError(Text.translatable("minebackup.message.unfreeze.already"));
                                return 0;
                            }
                            MineBackup.unfreezeAutoSave(server);
                            source.sendFeedback(() -> Text.translatable("minebackup.message.unfreeze.success"), true);
                            return 1;
                        })
                )
        );

        dispatcher.register(CommandManager.literal("minebackup")
                .requires(Command::hasCommandAccess)
                .executes(ctx -> {
                    ctx.getSource().sendFeedback(() -> Text.translatable("minebackup.message.command.migrated"), false);
                    if (ctx.getSource().getServer().isDedicated()) {
                        return sendPluginRedirect(ctx.getSource());
                    }
                    return 1;
                })
                .then(CommandManager.argument("args", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            ctx.getSource().sendFeedback(() -> Text.translatable("minebackup.message.command.migrated"), false);
                            if (ctx.getSource().getServer().isDedicated()) {
                                return sendPluginRedirect(ctx.getSource());
                            }
                            return 1;
                        })
                )
        );
    }

    private static boolean hasCommandAccess(ServerCommandSource source) {
        MinecraftServer server = source.getServer();
        if (server == null) {
            return false;
        }
        if (server.isDedicated()) {
            return source.hasPermissionLevel(2);
        }
        return isLocalHost(source);
    }

    private static boolean isLocalHost(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            return false;
        }
        GameProfile profile = player.getGameProfile();
        return profile != null && source.getServer().isHost(profile);
    }

    private static int executeDedicatedAware(ServerCommandSource source, Runnable action) {
        if (handleDedicatedServerUnsupported(source)) {
            return 1;
        }
        action.run();
        return 1;
    }

    private static boolean handleDedicatedServerUnsupported(ServerCommandSource source) {
        MinecraftServer server = source.getServer();
        if (server != null && server.isDedicated()) {
            sendPluginRedirect(source);
            return true;
        }
        return false;
    }

    private static int sendPluginRedirect(ServerCommandSource source) {
        source.sendError(Text.translatable("minebackup.message.plugin_required"));
        source.sendFeedback(Command::buildPluginLinkMessage, false);
        return 1;
    }

    private static MutableText buildPluginLinkMessage() {
        return Text.translatable("minebackup.message.plugin_link_prefix")
                .append(Text.literal(MineBackup.PLUGIN_GUIDE_URL).styled(style -> style
                        .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, MineBackup.PLUGIN_GUIDE_URL))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Text.translatable("minebackup.message.plugin_link_hover")))
                        .withUnderline(true)));
    }

    private static void queryBackend(String command, java.util.function.Consumer<String> callback) {
        CompletableFuture<String> future = OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, command);
        if (future == null) {
            callback.accept(null);
            return;
        }
        future.exceptionally(ex -> {
                    MineBackup.LOGGER.error("MineBackup communication failed: {}", ex.getMessage());
                    return "ERROR:COMMUNICATION_FAILED";
                })
                .thenAccept(response -> {
                    try {
                        callback.accept(response);
                    } catch (Exception e) {
                        MineBackup.LOGGER.error("Failed to process backend response: {}", e.getMessage());
                    }
                });
    }

    private static void saveAllWorlds(ServerCommandSource source) {
        MinecraftServer server = source.getServer();
        source.sendFeedback(() -> Text.translatable("minebackup.message.save.start"), false);
        for (ServerWorld world : server.getWorlds()) {
            world.save(null, true, false);
        }
        source.sendFeedback(() -> Text.translatable("minebackup.message.save.success"), false);
    }

    private static void handleGenericResponse(ServerCommandSource source, String response, String commandType) {
        source.getServer().execute(() -> {
            if (response == null || response.isBlank()) {
                source.sendError(Text.translatable("minebackup.message.command.fail",
                        Text.translatable("minebackup.message.no_response")));
            } else if (response.startsWith("ERROR:")) {
                source.sendError(Text.translatable("minebackup.message.command.fail", localizeErrorDetail(response)));
            } else {
                String detail = extractSuccessDetail(response);
                if (detail != null) {
                    source.sendFeedback(() -> Text.translatable("minebackup.message." + commandType + ".response", detail), false);
                }
            }
        });
    }

    private static Object localizeErrorDetail(String response) {
        if (response == null) {
            return Text.translatable("minebackup.message.no_response");
        }
        if (response.startsWith("ERROR:")) {
            String error = response.substring(6);
            return switch (error) {
                case "COMMUNICATION_FAILED" -> Text.translatable("minebackup.message.communication_failed");
                case "NO_RESPONSE" -> Text.translatable("minebackup.message.no_response");
                default -> error;
            };
        }
        return response;
    }

    private static int executeRemoteCommand(ServerCommandSource source, String command) {
        if (command == null || command.trim().isEmpty()) {
            source.sendError(Text.translatable("minebackup.message.command.invalid"));
            return 0;
        }
        if (handleDedicatedServerUnsupported(source)) {
            return 1;
        }
        source.sendFeedback(() -> Text.translatable("minebackup.message.command.sent", command), false);
        String commandType = normalizeCommandType(command.split(" ")[0].toLowerCase(Locale.ROOT));
        queryBackend(command, response -> handleGenericResponse(source, response, commandType));
        return 1;
    }

    private static String normalizeCommandType(String commandType) {
        return "restore_current_latest".equals(commandType) ? "restore_current" : commandType;
    }

    private static String extractSuccessDetail(String response) {
        String normalized = response == null ? "" : response.trim();
        if (normalized.isEmpty() || "OK".equalsIgnoreCase(normalized)) {
            return null;
        }
        if (normalized.regionMatches(true, 0, "OK:", 0, 3)) {
            String detail = normalized.substring(3).trim();
            return detail.isEmpty() ? null : detail;
        }
        return normalized;
    }

    private static String normalizeSuggestionInput(String remaining) {
        String normalized = remaining == null ? "" : remaining;
        if (!normalized.isEmpty() && normalized.charAt(0) == '\'') {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private static String quoteSuggestion(String value) {
        if (value.indexOf('\'') >= 0) {
            return null;
        }
        return "'" + value + "'";
    }

    private static String requireSingleQuotedString(CommandContext<ServerCommandSource> ctx, String argumentName) {
        String rawArgument = getRawArgument(ctx, argumentName);
        if (rawArgument == null || rawArgument.length() < 2 || rawArgument.charAt(0) != '\'' || rawArgument.charAt(rawArgument.length() - 1) != '\'') {
            ctx.getSource().sendError(Text.literal("[MineBackup] 文件名参数必须使用单引号，例如 'backup.zip'。"));
            return null;
        }
        return StringArgumentType.getString(ctx, argumentName);
    }

    private static String getRawArgument(CommandContext<ServerCommandSource> ctx, String argumentName) {
        String input = ctx.getInput();
        for (ParsedCommandNode<ServerCommandSource> node : ctx.getNodes()) {
            if (argumentName.equals(node.getNode().getName())) {
                int start = node.getRange().getStart();
                int end = node.getRange().getEnd();
                if (start >= 0 && end <= input.length() && start < end) {
                    return input.substring(start, end);
                }
            }
        }
        return null;
    }

    private static void handleListConfigsResponse(ServerCommandSource source, String response) {
        source.getServer().execute(() -> {
            if (response == null || !response.startsWith("OK:")) {
                source.sendError(Text.translatable("minebackup.message.list_configs.fail", localizeErrorDetail(response)));
                return;
            }
            final Text resultText;
            String data = response.substring(3);
            if (data.isEmpty()) {
                resultText = Text.translatable("minebackup.message.list_configs.empty");
            } else {
                StringBuilder builder = new StringBuilder();
                for (String config : data.split(";")) {
                    String[] parts = config.split(",", 2);
                    if (parts.length == 2) {
                        builder.append(Text.translatable("minebackup.message.list_configs.success.entry", parts[0], parts[1]).getString()).append("\n");
                    }
                }
                resultText = Text.literal(builder.toString());
            }
            source.sendFeedback(() -> resultText, false);
        });
    }

    private static void handleListWorldsResponse(ServerCommandSource source, String response, int configId) {
        source.getServer().execute(() -> {
            if (response == null || !response.startsWith("OK:")) {
                source.sendError(Text.translatable("minebackup.message.list_worlds.fail", localizeErrorDetail(response)));
                return;
            }
            final Text resultText;
            String data = response.substring(3);
            if (data.isEmpty()) {
                resultText = Text.translatable("minebackup.message.list_worlds.empty");
            } else {
                StringBuilder builder = new StringBuilder();
                String[] worlds = data.split(";");
                for (int i = 0; i < worlds.length; i++) {
                    builder.append(Text.translatable("minebackup.message.list_worlds.success.entry", String.valueOf(i), worlds[i]).getString()).append("\n");
                }
                resultText = Text.literal(builder.toString());
            }
            source.sendFeedback(() -> resultText, false);
        });
    }

    private static void handleListBackupsResponse(ServerCommandSource source, String response, int configId, int worldIndex) {
        source.getServer().execute(() -> {
            if (response == null || !response.startsWith("OK:")) {
                source.sendError(Text.translatable("minebackup.message.list_backups.fail", localizeErrorDetail(response)));
                return;
            }
            final Text resultText;
            String data = response.substring(3);
            if (data.isEmpty()) {
                resultText = Text.translatable("minebackup.message.list_backups.empty");
            } else {
                StringBuilder builder = new StringBuilder();
                for (String file : data.split(";")) {
                    if (!file.isEmpty()) {
                        builder.append(Text.translatable("minebackup.message.list_backups.success.entry", file).getString()).append("\n");
                    }
                }
                resultText = Text.literal(builder.toString());
            }
            source.sendFeedback(() -> resultText, false);
        });
    }

    private static CompletableFuture<Suggestions> suggestBackupFiles(int configId, int worldIndex, SuggestionsBuilder builder) {
        String command = String.format("LIST_BACKUPS %d %d", configId, worldIndex);
        return OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, command)
                .thenApply(response -> {
                    if (response != null && response.startsWith("OK:")) {
                        String remLower = normalizeSuggestionInput(builder.getRemaining()).toLowerCase(Locale.ROOT);
                        for (String file : response.substring(3).split(";")) {
                            String suggestion = quoteSuggestion(file);
                            if (!file.isEmpty() && suggestion != null && file.toLowerCase(Locale.ROOT).startsWith(remLower)) {
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

    private static CompletableFuture<Suggestions> suggestCurrentBackupFiles(SuggestionsBuilder builder) {
        return queryCurrentBackupsThrottled()
                .thenApply(response -> {
                    if (response != null && response.startsWith("OK:")) {
                        String remLower = normalizeSuggestionInput(builder.getRemaining()).toLowerCase(Locale.ROOT);
                        for (String file : response.substring(3).split(";")) {
                            String suggestion = quoteSuggestion(file);
                            if (!file.isEmpty() && suggestion != null && file.toLowerCase(Locale.ROOT).startsWith(remLower)) {
                                builder.suggest(suggestion);
                            }
                        }
                    }
                    return builder.build();
                })
                .exceptionally(ex -> {
                    MineBackup.LOGGER.warn("Failed to suggest current-world backup files: {}", ex.getMessage());
                    return builder.build();
                });
    }

    private static CompletableFuture<String> queryCurrentBackupsThrottled() {
        synchronized (Command.class) {
            long now = System.currentTimeMillis();
            if (now - lastCurrentBackupsQueryAtMs < CURRENT_BACKUPS_QUERY_INTERVAL_MS) {
                if (currentBackupsQueryFuture != null && !currentBackupsQueryFuture.isDone()) {
                    return currentBackupsQueryFuture;
                }
                return CompletableFuture.completedFuture(lastCurrentBackupsResponse);
            }

            lastCurrentBackupsQueryAtMs = now;
            CompletableFuture<String> future = OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, "LIST_BACKUPS_CURRENT");
            if (future == null) {
                return CompletableFuture.completedFuture(lastCurrentBackupsResponse);
            }

            currentBackupsQueryFuture = future.handle((response, ex) -> {
                synchronized (Command.class) {
                    currentBackupsQueryFuture = null;
                    if (ex == null && response != null && response.startsWith("OK:")) {
                        lastCurrentBackupsResponse = response;
                    }
                }
                if (ex != null) {
                    MineBackup.LOGGER.warn("Failed to query current-world backups: {}", ex.getMessage());
                    return lastCurrentBackupsResponse;
                }
                return response;
            });
            return currentBackupsQueryFuture;
        }
    }
}
