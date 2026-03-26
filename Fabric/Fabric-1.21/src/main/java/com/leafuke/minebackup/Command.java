package com.leafuke.minebackup;

import com.leafuke.minebackup.knotlink.OpenSocketQuerier;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.ParsedCommandNode;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public class Command {
    private static final String QUERIER_APP_ID = "0x00000020";
    private static final String QUERIER_SOCKET_ID = "0x00000010";

    private Command() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("mb")
                .requires(Command::hasCommandAccess)
                .then(CommandManager.literal("help")
                        .executes(ctx -> {
                            ctx.getSource().sendFeedback(() -> CommandHelpRegistry.buildRootHelp(), false);
                            return 1;
                        })
                        .then(CommandManager.argument("subcommand", StringArgumentType.word())
                                .suggests((ctx, builder) -> CommandHelpRegistry.suggestCommands(builder))
                                .executes(ctx -> {
                                    String subcommand = StringArgumentType.getString(ctx, "subcommand");
                                    ctx.getSource().sendFeedback(() -> CommandHelpRegistry.buildCommandHelp(subcommand), false);
                                    return 1;
                                })))
                .then(CommandManager.literal("save")
                    .executes(ctx -> saveAllWorlds(ctx.getSource()) ? 1 : 0))
                .then(CommandManager.literal("list_configs")
                    .executes(ctx -> {
                            ctx.getSource().sendFeedback(() -> Text.translatable("minebackup.message.list_configs.start"), false);
                            queryBackend("LIST_CONFIGS", response -> handleListConfigsResponse(ctx.getSource(), response));
                        return 1;
                    }))
                .then(CommandManager.literal("list_worlds")
                        .then(CommandManager.argument("config_id", StringArgumentType.string())
                                .suggests((ctx, builder) -> CommandSuggestions.suggestConfigIds(builder))
                        .executes(ctx -> {
                                    String configId = StringArgumentType.getString(ctx, "config_id");
                                    ctx.getSource().sendFeedback(() -> Text.translatable("minebackup.message.list_worlds.start", configId), false);
                                    queryBackend(String.format("LIST_WORLDS %s", configId),
                                            response -> handleListWorldsResponse(ctx.getSource(), response, configId));
                            return 1;
                        })))
                .then(CommandManager.literal("list_backups")
                        .then(CommandManager.argument("config_id", StringArgumentType.string())
                                .suggests((ctx, builder) -> CommandSuggestions.suggestConfigIds(builder))
                                .then(CommandManager.argument("world_index", IntegerArgumentType.integer())
                                        .suggests((ctx, builder) -> CommandSuggestions.suggestWorldIndices(
                                                StringArgumentType.getString(ctx, "config_id"), builder))
                            .executes(ctx -> {
                                            String configId = StringArgumentType.getString(ctx, "config_id");
                                            int worldIndex = IntegerArgumentType.getInteger(ctx, "world_index");
                                            ctx.getSource().sendFeedback(
                                                    () -> Text.translatable("minebackup.message.list_backups.start", configId, String.valueOf(worldIndex)),
                                                    false);
                                            queryBackend(String.format("LIST_BACKUPS %s %d", configId, worldIndex),
                                                    response -> handleListBackupsResponse(ctx.getSource(), response, configId, worldIndex));
                                return 1;
                            }))))
                .then(CommandManager.literal("backup")
                        .then(CommandManager.argument("config_id", StringArgumentType.string())
                                .suggests((ctx, builder) -> CommandSuggestions.suggestConfigIds(builder))
                                .then(CommandManager.argument("world_index", IntegerArgumentType.integer())
                                        .suggests((ctx, builder) -> CommandSuggestions.suggestWorldIndices(
                                                StringArgumentType.getString(ctx, "config_id"), builder))
                                        .executes(ctx -> executeRemoteCommand(ctx.getSource(),
                                                String.format("BACKUP %s %d",
                                                        StringArgumentType.getString(ctx, "config_id"),
                                                        IntegerArgumentType.getInteger(ctx, "world_index"))))
                                        .then(CommandManager.argument("comment", StringArgumentType.greedyString())
                                                .executes(ctx -> executeRemoteCommand(ctx.getSource(),
                                                        String.format("BACKUP %s %d %s",
                                                                StringArgumentType.getString(ctx, "config_id"),
                                                                IntegerArgumentType.getInteger(ctx, "world_index"),
                                                                StringArgumentType.getString(ctx, "comment"))))))))
                .then(CommandManager.literal("restore")
                        .then(CommandManager.argument("config_id", StringArgumentType.string())
                                .suggests((ctx, builder) -> CommandSuggestions.suggestConfigIds(builder))
                                .then(CommandManager.argument("world_index", IntegerArgumentType.integer())
                                        .suggests((ctx, builder) -> CommandSuggestions.suggestWorldIndices(
                                                StringArgumentType.getString(ctx, "config_id"), builder))
                                        .then(CommandManager.argument("backup_file", StringArgumentType.string())
                                                .suggests((ctx, builder) -> CommandSuggestions.suggestBackupFiles(
                                                        StringArgumentType.getString(ctx, "config_id"),
                                                        IntegerArgumentType.getInteger(ctx, "world_index"),
                                                        builder))
                                                .executes(ctx -> {
                                                    if (handleDedicatedRestoreUnsupported(ctx.getSource())) {
                                                        return 1;
                                                    }
                                                    String backupFile = requireSingleQuotedString(ctx, "backup_file");
                                                    if (backupFile == null) {
                                                        return 0;
                                                    }
                                                    return executeRemoteCommand(ctx.getSource(),
                                                            String.format("RESTORE %s %d %s",
                                                                    StringArgumentType.getString(ctx, "config_id"),
                                                                    IntegerArgumentType.getInteger(ctx, "world_index"),
                                                                    backupFile));
                                                })))))
                .then(CommandManager.literal("quickbackup")
                        .executes(ctx -> executeQuickBackup(ctx.getSource(), null))
                        .then(CommandManager.argument("comment", StringArgumentType.greedyString())
                                .executes(ctx -> executeQuickBackup(ctx.getSource(), StringArgumentType.getString(ctx, "comment")))))
                .then(CommandManager.literal("quicksave")
                        .executes(ctx -> executeQuickBackup(ctx.getSource(), null))
                        .then(CommandManager.argument("comment", StringArgumentType.greedyString())
                                .executes(ctx -> executeQuickBackup(ctx.getSource(), StringArgumentType.getString(ctx, "comment")))))
                .then(CommandManager.literal("quickrestore")
                        .executes(ctx -> {
                            if (handleDedicatedRestoreUnsupported(ctx.getSource())) {
                                return 1;
                            }
                            return executeRemoteCommand(ctx.getSource(), "RESTORE_CURRENT_LATEST");
                        })
                        .then(CommandManager.argument("backup_file", StringArgumentType.string())
                                .suggests((ctx, builder) -> CommandSuggestions.suggestCurrentBackupFiles(builder))
                                .executes(ctx -> {
                                    if (handleDedicatedRestoreUnsupported(ctx.getSource())) {
                                        return 1;
                                    }
                                    String backupFile = requireSingleQuotedString(ctx, "backup_file");
                                    if (backupFile == null) {
                                        return 0;
                                    }
                                    return executeRemoteCommand(ctx.getSource(), String.format("RESTORE_CURRENT %s", backupFile));
                                })))
                .then(CommandManager.literal("auto")
                        .then(CommandManager.argument("config_id", StringArgumentType.string())
                                .suggests((ctx, builder) -> CommandSuggestions.suggestConfigIds(builder))
                                .then(CommandManager.argument("world_index", IntegerArgumentType.integer())
                                        .suggests((ctx, builder) -> CommandSuggestions.suggestWorldIndices(
                                                StringArgumentType.getString(ctx, "config_id"), builder))
                                        .then(CommandManager.argument("internal_time", IntegerArgumentType.integer())
                                                .executes(ctx -> {
                                                    String configId = StringArgumentType.getString(ctx, "config_id");
                                                    int worldIndex = IntegerArgumentType.getInteger(ctx, "world_index");
                                                    int internalTime = IntegerArgumentType.getInteger(ctx, "internal_time");
                                                    Config.setAutoBackup(configId, worldIndex, internalTime);
                                                    return executeRemoteCommand(ctx.getSource(),
                                                            String.format("AUTO_BACKUP %s %d %d", configId, worldIndex, internalTime));
                                                })))))
                .then(CommandManager.literal("stop")
                        .then(CommandManager.argument("config_id", StringArgumentType.string())
                                .suggests((ctx, builder) -> CommandSuggestions.suggestConfigIds(builder))
                                .then(CommandManager.argument("world_index", IntegerArgumentType.integer())
                                        .suggests((ctx, builder) -> CommandSuggestions.suggestWorldIndices(
                                                StringArgumentType.getString(ctx, "config_id"), builder))
                                        .executes(ctx -> {
                                            String configId = StringArgumentType.getString(ctx, "config_id");
                                            int worldIndex = IntegerArgumentType.getInteger(ctx, "world_index");
                                            Config.clearAutoBackup();
                                            return executeRemoteCommand(ctx.getSource(),
                                                    String.format("STOP_AUTO_BACKUP %s %d", configId, worldIndex));
                                        }))))
                .then(CommandManager.literal("snap")
                        .then(CommandManager.argument("config_id", StringArgumentType.string())
                                .suggests((ctx, builder) -> CommandSuggestions.suggestConfigIds(builder))
                                .then(CommandManager.argument("world_index", IntegerArgumentType.integer())
                                        .suggests((ctx, builder) -> CommandSuggestions.suggestWorldIndices(
                                                StringArgumentType.getString(ctx, "config_id"), builder))
                                        .then(CommandManager.argument("backup_file", StringArgumentType.string())
                                                .suggests((ctx, builder) -> CommandSuggestions.suggestBackupFiles(
                                                        StringArgumentType.getString(ctx, "config_id"),
                                                        IntegerArgumentType.getInteger(ctx, "world_index"),
                                                        builder))
                                                .executes(ctx -> {
                                                    String backupFile = requireSingleQuotedString(ctx, "backup_file");
                                                    if (backupFile == null) {
                                                        return 0;
                                                    }
                                                    String command = String.format("ADD_TO_WE %s %d %s",
                                                            StringArgumentType.getString(ctx, "config_id"),
                                                            IntegerArgumentType.getInteger(ctx, "world_index"),
                                                            backupFile);
                                                    ctx.getSource().sendFeedback(() -> Text.translatable("minebackup.message.snap.sent", command), false);
                                                        queryBackend(command, response -> handleGenericResponse(ctx.getSource(), response, "snap"));
                                                        return 1;
                                                })))))
                .then(CommandManager.literal("freeze")
                        .executes(ctx -> {
                                            if (handleDedicatedFreezeUnsupported(ctx.getSource())) {
                                return 1;
                            }
                            if (MineBackup.isSaveFrozen()) {
                                ctx.getSource().sendError(Text.translatable("minebackup.message.freeze.already"));
                                return 0;
                            }
                            if (!saveAllWorlds(ctx.getSource())) {
                                return 0;
                            }
                            MineBackup.freezeAutoSave(ctx.getSource().getServer());
                            ctx.getSource().sendFeedback(() -> Text.translatable("minebackup.message.freeze.success"), true);
                            return 1;
                        }))
                .then(CommandManager.literal("unfreeze")
                        .executes(ctx -> {
                            if (handleDedicatedFreezeUnsupported(ctx.getSource())) {
                                return 1;
                            }
                            if (!MineBackup.isSaveFrozen()) {
                                ctx.getSource().sendError(Text.translatable("minebackup.message.unfreeze.already"));
                                return 0;
                            }
                            MineBackup.unfreezeAutoSave(ctx.getSource().getServer());
                            ctx.getSource().sendFeedback(() -> Text.translatable("minebackup.message.unfreeze.success"), true);
                            return 1;
                        }))
        );

        dispatcher.register(CommandManager.literal("minebackup")
                .requires(Command::hasCommandAccess)
                .executes(ctx -> {
                    ctx.getSource().sendFeedback(() -> Text.translatable("minebackup.message.command.migrated"), false);
                    return 1;
                })
                .then(CommandManager.argument("args", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            ctx.getSource().sendFeedback(() -> Text.translatable("minebackup.message.command.migrated"), false);
                            return 1;
                        })));
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

    private static boolean handleDedicatedRestoreUnsupported(ServerCommandSource source) {
        MinecraftServer server = source.getServer();
        if (server != null && server.isDedicated()) {
            source.sendError(Text.translatable("minebackup.message.restore.unsupported_dedicated"));
            source.sendFeedback(Command::buildPluginLinkMessage, false);
            return true;
        }
        return false;
    }

    private static boolean handleDedicatedFreezeUnsupported(ServerCommandSource source) {
        MinecraftServer server = source.getServer();
        if (server != null && server.isDedicated()) {
            source.sendError(Text.translatable("minebackup.message.freeze.unsupported_dedicated"));
            return true;
        }
        return false;
    }

    private static MutableText buildPluginLinkMessage() {
        return Text.translatable("minebackup.message.plugin_link_prefix")
                .append(Text.literal(MineBackup.PLUGIN_GUIDE_URL).styled(style -> style
                        .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, MineBackup.PLUGIN_GUIDE_URL))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Text.translatable("minebackup.message.plugin_link_hover")))
                        .withUnderline(true)));
    }

    private static int executeQuickBackup(ServerCommandSource source, String comment) {
        if (!saveAllWorlds(source)) {
            return 0;
        }

        String command = comment == null || comment.isBlank()
                ? "BACKUP_CURRENT"
                : String.format("BACKUP_CURRENT %s", comment);
        return executeRemoteCommand(source, command);
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

    private static boolean saveAllWorlds(ServerCommandSource source) {
        source.sendFeedback(() -> Text.translatable("minebackup.message.save.start"), false);
        boolean success = LocalSaveCoordinator.saveForLocalCommand(source.getServer());
        if (success) {
            source.sendFeedback(() -> Text.translatable("minebackup.message.save.success"), false);
        } else {
            source.sendError(Text.translatable("minebackup.message.remote_save.fail"));
        }
        return success;
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

    private static String requireSingleQuotedString(CommandContext<ServerCommandSource> ctx, String argumentName) {
        String rawArgument = getRawArgument(ctx, argumentName);
        if (rawArgument == null || rawArgument.length() < 2 || rawArgument.charAt(0) != '\'' || rawArgument.charAt(rawArgument.length() - 1) != '\'') {
            ctx.getSource().sendError(Text.literal("Backup file must use single quotes, for example: 'backup.7z'"));
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
            MutableText resultText = Text.translatable("minebackup.message.list_configs.success.title");
            String data = response.substring(3);
            if (data.isEmpty()) {
                resultText.append(Text.translatable("minebackup.message.list_configs.empty"));
            } else {
                for (String config : data.split(";")) {
                    String[] parts = config.split(",", 2);
                    if (parts.length == 2) {
                        resultText.append(Text.translatable("minebackup.message.list_configs.success.entry", parts[0], parts[1]));
                    }
                }
            }
            source.sendFeedback(() -> resultText, false);
        });
    }

    private static void handleListWorldsResponse(ServerCommandSource source, String response, String configId) {
        source.getServer().execute(() -> {
            if (response == null || !response.startsWith("OK:")) {
                source.sendError(Text.translatable("minebackup.message.list_worlds.fail", localizeErrorDetail(response)));
                return;
            }
            MutableText resultText = Text.translatable("minebackup.message.list_worlds.success.title", configId);
            String data = response.substring(3);
            if (data.isEmpty()) {
                resultText.append(Text.translatable("minebackup.message.list_worlds.empty"));
            } else {
                String[] worlds = data.split(";");
                for (int i = 0; i < worlds.length; i++) {
                    resultText.append(Text.translatable("minebackup.message.list_worlds.success.entry", String.valueOf(i), worlds[i]));
                }
            }
            source.sendFeedback(() -> resultText, false);
        });
    }

    private static void handleListBackupsResponse(ServerCommandSource source, String response, String configId, int worldIndex) {
        source.getServer().execute(() -> {
            if (response == null || !response.startsWith("OK:")) {
                source.sendError(Text.translatable("minebackup.message.list_backups.fail", localizeErrorDetail(response)));
                return;
            }
            MutableText resultText = Text.translatable("minebackup.message.list_backups.success.title", configId, String.valueOf(worldIndex));
            String data = response.substring(3);
            if (data.isEmpty()) {
                resultText.append(Text.translatable("minebackup.message.list_backups.empty"));
            } else {
                for (String file : data.split(";")) {
                    if (!file.isEmpty()) {
                        resultText.append(Text.translatable("minebackup.message.list_backups.success.entry", file));
                    }
                }
            }
            source.sendFeedback(() -> resultText, false);
        });
    }
}
