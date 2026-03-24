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
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public class Command {
    private static final String QUERIER_APP_ID = "0x00000020";
    private static final String QUERIER_SOCKET_ID = "0x00000010";
    private static final long CURRENT_BACKUPS_QUERY_INTERVAL_MS = 5000L;
    private static volatile long lastCurrentBackupsQueryAtMs = 0L;
    private static volatile String lastCurrentBackupsResponse = null;
    private static CompletableFuture<String> currentBackupsQueryFuture = null;

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context, Commands.CommandSelection selection) {
        dispatcher.register(Commands.literal("mb")
                .requires(Command::hasCommandAccess)
                .then(Commands.literal("save")
                        .executes(ctx -> {
                            if (handleDedicatedServerUnsupported(ctx.getSource())) {
                                return 1;
                            }
                            saveAllWorlds(ctx.getSource());
                            return 1;
                        })
                )
                .then(Commands.literal("list_configs")
                        .executes(ctx -> executeDedicatedAware(ctx.getSource(), () -> {
                            ctx.getSource().sendSuccess(() -> Component.translatable("minebackup.message.list_configs.start"), false);
                            queryBackend("LIST_CONFIGS", response -> handleListConfigsResponse(ctx.getSource(), response));
                        }))
                )
                .then(Commands.literal("list_worlds")
                        .then(Commands.argument("config_id", IntegerArgumentType.integer())
                                .executes(ctx -> executeDedicatedAware(ctx.getSource(), () -> {
                                    int configId = IntegerArgumentType.getInteger(ctx, "config_id");
                                    ctx.getSource().sendSuccess(() -> Component.translatable("minebackup.message.list_worlds.start", String.valueOf(configId)), false);
                                    queryBackend(
                                            String.format("LIST_WORLDS %d", configId),
                                            response -> handleListWorldsResponse(ctx.getSource(), response, configId)
                                    );
                                }))
                        )
                )
                .then(Commands.literal("list_backups")
                        .then(Commands.argument("config_id", IntegerArgumentType.integer())
                                .then(Commands.argument("world_index", IntegerArgumentType.integer())
                                        .executes(ctx -> executeDedicatedAware(ctx.getSource(), () -> {
                                            int configId = IntegerArgumentType.getInteger(ctx, "config_id");
                                            int worldIndex = IntegerArgumentType.getInteger(ctx, "world_index");
                                            ctx.getSource().sendSuccess(() -> Component.translatable("minebackup.message.list_backups.start", String.valueOf(configId), String.valueOf(worldIndex)), false);
                                            queryBackend(
                                                    String.format("LIST_BACKUPS %d %d", configId, worldIndex),
                                                    response -> handleListBackupsResponse(ctx.getSource(), response, configId, worldIndex)
                                            );
                                        }))
                                )
                        )
                )
                .then(Commands.literal("backup")
                        .then(Commands.argument("config_id", IntegerArgumentType.integer())
                                .then(Commands.argument("world_index", IntegerArgumentType.integer())
                                        .executes(ctx -> executeRemoteCommand(ctx.getSource(),
                                                String.format("BACKUP %d %d",
                                                        IntegerArgumentType.getInteger(ctx, "config_id"),
                                                        IntegerArgumentType.getInteger(ctx, "world_index"))))
                                        .then(Commands.argument("comment", StringArgumentType.greedyString())
                                                .executes(ctx -> executeRemoteCommand(ctx.getSource(),
                                                        String.format("BACKUP %d %d %s",
                                                                IntegerArgumentType.getInteger(ctx, "config_id"),
                                                                IntegerArgumentType.getInteger(ctx, "world_index"),
                                                                StringArgumentType.getString(ctx, "comment"))))
                                        )
                                )
                        )
                )
                .then(Commands.literal("restore")
                        .then(Commands.argument("config_id", IntegerArgumentType.integer())
                                .then(Commands.argument("world_index", IntegerArgumentType.integer())
                                        .then(Commands.argument("backup_file", StringArgumentType.string())
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
                .then(Commands.literal("quicksave")
                        .executes(ctx -> executeRemoteCommand(ctx.getSource(), "BACKUP_CURRENT"))
                        .then(Commands.argument("comment", StringArgumentType.greedyString())
                                .executes(ctx -> executeRemoteCommand(ctx.getSource(),
                                        String.format("BACKUP_CURRENT %s", StringArgumentType.getString(ctx, "comment"))))
                        )
                )
                .then(Commands.literal("quickrestore")
                        .executes(ctx -> executeRemoteCommand(ctx.getSource(), "RESTORE_CURRENT_LATEST"))
                        .then(Commands.argument("backup_file", StringArgumentType.string())
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
                .then(Commands.literal("auto")
                        .then(Commands.argument("config_id", IntegerArgumentType.integer())
                                .then(Commands.argument("world_index", IntegerArgumentType.integer())
                                        .then(Commands.argument("internal_time", IntegerArgumentType.integer())
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
                .then(Commands.literal("stop")
                        .then(Commands.argument("config_id", IntegerArgumentType.integer())
                                .then(Commands.argument("world_index", IntegerArgumentType.integer())
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
                .then(Commands.literal("snap")
                        .then(Commands.argument("config_id", IntegerArgumentType.integer())
                                .then(Commands.argument("world_index", IntegerArgumentType.integer())
                                        .then(Commands.argument("backup_file", StringArgumentType.string())
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
                                                    ctx.getSource().sendSuccess(() -> Component.translatable("minebackup.message.snap.sent", command), false);
                                                    return executeDedicatedAware(ctx.getSource(),
                                                            () -> queryBackend(command, response -> handleGenericResponse(ctx.getSource(), response, "snap")));
                                                })
                                        )
                                )
                        )
                )
                .then(Commands.literal("freeze")
                        .executes(ctx -> {
                            if (handleDedicatedServerUnsupported(ctx.getSource())) {
                                return 1;
                            }
                            if (MineBackup.isSaveFrozen()) {
                                ctx.getSource().sendFailure(Component.translatable("minebackup.message.freeze.already"));
                                return 0;
                            }
                            saveAllWorlds(ctx.getSource());
                            MineBackup.freezeAutoSave(ctx.getSource().getServer());
                            ctx.getSource().sendSuccess(() -> Component.translatable("minebackup.message.freeze.success"), true);
                            return 1;
                        })
                )
                .then(Commands.literal("unfreeze")
                        .executes(ctx -> {
                            if (handleDedicatedServerUnsupported(ctx.getSource())) {
                                return 1;
                            }
                            if (!MineBackup.isSaveFrozen()) {
                                ctx.getSource().sendFailure(Component.translatable("minebackup.message.unfreeze.already"));
                                return 0;
                            }
                            MineBackup.unfreezeAutoSave(ctx.getSource().getServer());
                            ctx.getSource().sendSuccess(() -> Component.translatable("minebackup.message.unfreeze.success"), true);
                            return 1;
                        })
                )
        );

        dispatcher.register(Commands.literal("minebackup")
                .requires(Command::hasCommandAccess)
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.translatable("minebackup.message.command.migrated"), false);
                    if (ctx.getSource().getServer().isDedicatedServer()) {
                        return sendPluginRedirect(ctx.getSource());
                    }
                    return 1;
                })
                .then(Commands.argument("args", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            ctx.getSource().sendSuccess(() -> Component.translatable("minebackup.message.command.migrated"), false);
                            if (ctx.getSource().getServer().isDedicatedServer()) {
                                return sendPluginRedirect(ctx.getSource());
                            }
                            return 1;
                        })));
    }

    private static boolean hasCommandAccess(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        if (server == null) {
            return false;
        }
        if (server.isDedicatedServer()) {
            return source.hasPermission(2);
        }
        return isLocalHost(source);
    }

    private static boolean isLocalHost(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return false;
        }
        GameProfile profile = player.getGameProfile();
        return profile != null && source.getServer().isSingleplayerOwner(profile);
    }

    private static int executeDedicatedAware(CommandSourceStack source, Runnable action) {
        if (handleDedicatedServerUnsupported(source)) {
            return 1;
        }
        action.run();
        return 1;
    }

    private static boolean handleDedicatedServerUnsupported(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        if (server != null && server.isDedicatedServer()) {
            sendPluginRedirect(source);
            return true;
        }
        return false;
    }

    private static int sendPluginRedirect(CommandSourceStack source) {
        source.sendFailure(Component.translatable("minebackup.message.plugin_required"));
        source.sendSuccess(Command::buildPluginLinkMessage, false);
        return 1;
    }

    private static MutableComponent buildPluginLinkMessage() {
        return Component.translatable("minebackup.message.plugin_link_prefix")
                .append(Component.literal(MineBackup.PLUGIN_GUIDE_URL).withStyle(style -> style
                        .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, MineBackup.PLUGIN_GUIDE_URL))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.translatable("minebackup.message.plugin_link_hover")))
                        .withUnderlined(true)));
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
                .thenAccept(resp -> {
                    try {
                        callback.accept(resp);
                    } catch (Exception e) {
                        MineBackup.LOGGER.error("Failed to process backend response: {}", e.getMessage());
                    }
                });
    }

    private static void saveAllWorlds(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        source.sendSuccess(() -> Component.translatable("minebackup.message.save.start"), false);
        for (ServerLevel level : server.getAllLevels()) {
            level.save(null, true, false);
        }
        source.sendSuccess(() -> Component.translatable("minebackup.message.save.success"), false);
    }

    private static void handleGenericResponse(CommandSourceStack source, String response, String commandType) {
        source.getServer().execute(() -> {
            if (response == null || response.isBlank()) {
                source.sendFailure(Component.translatable("minebackup.message.command.fail",
                        Component.translatable("minebackup.message.no_response")));
            } else if (response.startsWith("ERROR:")) {
                source.sendFailure(Component.translatable("minebackup.message.command.fail", localizeErrorDetail(response)));
            } else {
                String detail = extractSuccessDetail(response);
                if (detail != null) {
                    source.sendSuccess(() -> Component.translatable("minebackup.message." + commandType + ".response", detail), false);
                }
            }
        });
    }

    private static Object localizeErrorDetail(String response) {
        if (response == null) {
            return Component.translatable("minebackup.message.no_response");
        }
        if (response.startsWith("ERROR:")) {
            String error = response.substring(6);
            return switch (error) {
                case "COMMUNICATION_FAILED" -> Component.translatable("minebackup.message.communication_failed");
                case "NO_RESPONSE" -> Component.translatable("minebackup.message.no_response");
                default -> error;
            };
        }
        return response;
    }

    private static int executeRemoteCommand(CommandSourceStack source, String command) {
        if (command == null || command.trim().isEmpty()) {
            source.sendFailure(Component.translatable("minebackup.message.command.invalid"));
            return 0;
        }
        if (handleDedicatedServerUnsupported(source)) {
            return 1;
        }
        source.sendSuccess(() -> Component.translatable("minebackup.message.command.sent", command), false);
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

    private static String requireSingleQuotedString(CommandContext<CommandSourceStack> ctx, String argumentName) {
        String rawArgument = getRawArgument(ctx, argumentName);
        if (rawArgument == null || rawArgument.length() < 2 || rawArgument.charAt(0) != '\'' || rawArgument.charAt(rawArgument.length() - 1) != '\'') {
            ctx.getSource().sendFailure(Component.literal("[MineBackup] 文件名参数必须使用单引号，例如 'backup.zip'。"));
            return null;
        }
        return StringArgumentType.getString(ctx, argumentName);
    }

    private static String getRawArgument(CommandContext<CommandSourceStack> ctx, String argumentName) {
        String input = ctx.getInput();
        for (ParsedCommandNode<CommandSourceStack> node : ctx.getNodes()) {
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

    private static void handleListConfigsResponse(CommandSourceStack source, String response) {
        source.getServer().execute(() -> {
            if (response == null || !response.startsWith("OK:")) {
                source.sendFailure(Component.translatable("minebackup.message.list_configs.fail", localizeErrorDetail(response)));
                return;
            }
            MutableComponent resultText = Component.translatable("minebackup.message.list_configs.success.title");
            String data = response.substring(3);
            if (data.isEmpty()) {
                resultText.append(Component.translatable("minebackup.message.list_configs.empty"));
            } else {
                for (String config : data.split(";")) {
                    String[] parts = config.split(",", 2);
                    if (parts.length == 2) {
                        resultText.append(Component.translatable("minebackup.message.list_configs.success.entry", parts[0], parts[1]));
                    }
                }
            }
            source.sendSuccess(() -> resultText, false);
        });
    }

    private static void handleListWorldsResponse(CommandSourceStack source, String response, int configId) {
        source.getServer().execute(() -> {
            if (response == null || !response.startsWith("OK:")) {
                source.sendFailure(Component.translatable("minebackup.message.list_worlds.fail", localizeErrorDetail(response)));
                return;
            }
            MutableComponent resultText = Component.translatable("minebackup.message.list_worlds.success.title", String.valueOf(configId));
            String data = response.substring(3);
            if (data.isEmpty()) {
                resultText.append(Component.translatable("minebackup.message.list_worlds.empty"));
            } else {
                String[] worlds = data.split(";");
                for (int i = 0; i < worlds.length; i++) {
                    resultText.append(Component.translatable("minebackup.message.list_worlds.success.entry", String.valueOf(i), worlds[i]));
                }
            }
            source.sendSuccess(() -> resultText, false);
        });
    }

    private static void handleListBackupsResponse(CommandSourceStack source, String response, int configId, int worldIndex) {
        source.getServer().execute(() -> {
            if (response == null || !response.startsWith("OK:")) {
                source.sendFailure(Component.translatable("minebackup.message.list_backups.fail", localizeErrorDetail(response)));
                return;
            }
            MutableComponent resultText = Component.translatable("minebackup.message.list_backups.success.title", String.valueOf(configId), String.valueOf(worldIndex));
            String data = response.substring(3);
            if (data.isEmpty()) {
                resultText.append(Component.translatable("minebackup.message.list_backups.empty"));
            } else {
                for (String file : data.split(";")) {
                    if (!file.isEmpty()) {
                        resultText.append(Component.translatable("minebackup.message.list_backups.success.entry", file));
                    }
                }
            }
            source.sendSuccess(() -> resultText, false);
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
