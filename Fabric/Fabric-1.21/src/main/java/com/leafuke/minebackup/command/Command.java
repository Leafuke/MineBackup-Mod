package com.leafuke.minebackup.command;

import com.leafuke.minebackup.MineBackup;
import com.leafuke.minebackup.api.v2.BackupRequest;
import com.leafuke.minebackup.api.v2.BackupResult;
import com.leafuke.minebackup.api.v2.OperationFailure;
import com.leafuke.minebackup.api.v2.OperationHandle;
import com.leafuke.minebackup.api.v2.OperationPhase;
import com.leafuke.minebackup.api.v2.RestoreRequest;
import com.leafuke.minebackup.api.v2.RestoreResult;
import com.leafuke.minebackup.config.Config;
import com.leafuke.minebackup.knotlink.protocol.KnotLinkRequest;
import com.leafuke.minebackup.knotlink.protocol.KnotLinkResponse;
import com.leafuke.minebackup.runtime.LocalSaveCoordinator;
import com.leafuke.minebackup.runtime.AutoBackupUpdateResult;
import com.leafuke.minebackup.runtime.RestoreControlResult;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.command.CommandManager;
import net.minecraft.text.Text;
import net.minecraft.text.MutableText;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;

import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public final class Command {
    private Command() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("mb")
                .requires(Command::hasCommandAccess)
                .executes(context -> sendHelp(context.getSource()))
                .then(CommandManager.literal("help")
                        .executes(context -> sendHelp(context.getSource()))
                        .then(CommandManager.argument("command", StringArgumentType.greedyString())
                                .suggests((context, builder) -> CommandHelpRegistry.suggestCommands(builder))
                                .executes(context -> {
                                    String command = StringArgumentType.getString(context, "command");
                                    context.getSource().sendFeedback(
                                            () -> CommandHelpRegistry.buildCommandHelp(command),
                                            false);
                                    return 1;
                                })))
                .then(CommandManager.literal("save")
                        .executes(context -> saveCurrentWorld(context.getSource()) ? 1 : 0))
                .then(CommandManager.literal("backup")
                        .executes(context -> executeBackupCurrent(context.getSource(), null))
                        .then(CommandManager.argument("comment", StringArgumentType.greedyString())
                                .executes(context -> executeBackupCurrent(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "comment")))))
                .then(CommandManager.literal("restore")
                        .executes(context -> executeRestoreCurrent(context.getSource(), null))
                        .then(CommandManager.argument("file", StringArgumentType.string())
                                .suggests((context, builder) -> CommandSuggestions.suggestCurrentBackups(builder))
                                .executes(context -> executeRestoreCurrent(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "file")))))
                .then(CommandManager.literal("confirm")
                        .executes(context -> executeRestoreConfirm(context.getSource())))
                .then(CommandManager.literal("stop")
                        .executes(context -> executeRestoreStop(context.getSource())))
                .then(buildTargetCommands())
                .then(buildListCommands())
                .then(buildAutoCommands()));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> buildTargetCommands() {
        return CommandManager.literal("target")
                .then(CommandManager.literal("backup")
                        .then(CommandManager.argument("config_id", StringArgumentType.word())
                                .suggests((context, builder) -> CommandSuggestions.suggestConfigIds(builder))
                                .then(CommandManager.argument("folder", StringArgumentType.string())
                                        .suggests((context, builder) -> CommandSuggestions.suggestFolders(
                                                StringArgumentType.getString(context, "config_id"),
                                                builder))
                                        .executes(context -> executeBackupTarget(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "config_id"),
                                                StringArgumentType.getString(context, "folder"),
                                                null))
                                        .then(CommandManager.argument("comment", StringArgumentType.greedyString())
                                                .executes(context -> executeBackupTarget(
                                                        context.getSource(),
                                                        StringArgumentType.getString(context, "config_id"),
                                                        StringArgumentType.getString(context, "folder"),
                                                        StringArgumentType.getString(context, "comment")))))))
                .then(CommandManager.literal("restore")
                        .then(CommandManager.argument("config_id", StringArgumentType.word())
                                .suggests((context, builder) -> CommandSuggestions.suggestConfigIds(builder))
                                .then(CommandManager.argument("folder", StringArgumentType.string())
                                        .suggests((context, builder) -> CommandSuggestions.suggestFolders(
                                                StringArgumentType.getString(context, "config_id"),
                                                builder))
                                        .then(CommandManager.argument("file", StringArgumentType.string())
                                                .suggests((context, builder) -> CommandSuggestions.suggestBackups(
                                                        StringArgumentType.getString(context, "config_id"),
                                                        StringArgumentType.getString(context, "folder"),
                                                        builder))
                                                .executes(context -> executeRestoreTarget(
                                                        context.getSource(),
                                                        StringArgumentType.getString(context, "config_id"),
                                                        StringArgumentType.getString(context, "folder"),
                                                        StringArgumentType.getString(context, "file")))))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> buildListCommands() {
        return CommandManager.literal("list")
                .then(CommandManager.literal("configs")
                        .executes(context -> executeListConfigs(context.getSource())))
                .then(CommandManager.literal("folders")
                        .then(CommandManager.argument("config_id", StringArgumentType.word())
                                .suggests((context, builder) -> CommandSuggestions.suggestConfigIds(builder))
                                .executes(context -> executeListFolders(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "config_id")))))
                .then(CommandManager.literal("backups")
                        .then(CommandManager.argument("config_id", StringArgumentType.word())
                                .suggests((context, builder) -> CommandSuggestions.suggestConfigIds(builder))
                                .then(CommandManager.argument("folder", StringArgumentType.string())
                                        .suggests((context, builder) -> CommandSuggestions.suggestFolders(
                                                StringArgumentType.getString(context, "config_id"),
                                                builder))
                                        .executes(context -> executeListBackups(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "config_id"),
                                                StringArgumentType.getString(context, "folder"))))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> buildAutoCommands() {
        return CommandManager.literal("auto")
                .then(CommandManager.literal("start")
                        .then(CommandManager.argument(
                                        "minutes",
                                        IntegerArgumentType.integer(
                                                1,
                                                Config.MAX_AUTO_BACKUP_INTERVAL_MINUTES))
                                .executes(context -> executeAutoStart(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "minutes")))))
                .then(CommandManager.literal("stop")
                        .executes(context -> executeAutoStop(context.getSource())));
    }

    private static int executeBackupCurrent(ServerCommandSource source, String comment) {
        OperationHandle<BackupResult> handle = MineBackup.api().backupCurrent(
                BackupRequest.create(callerId(source), comment));
        source.sendFeedback(
                () -> Text.translatable("minebackup.message.command.sent", "BACKUP"),
                false);
        observeBackup(source, handle);
        return handle.phase() == OperationPhase.REJECTED ? 0 : 1;
    }

    private static int executeRestoreCurrent(ServerCommandSource source, String file) {
        warnAboutVoxy(source);
        RestoreRequest request = file == null || file.isBlank()
                ? RestoreRequest.latest(callerId(source))
                : RestoreRequest.file(callerId(source), file);
        OperationHandle<RestoreResult> handle = MineBackup.api().restoreCurrent(request);
        observeRestore(source, handle);
        return handle.phase() == OperationPhase.REJECTED ? 0 : 1;
    }

    private static int executeBackupTarget(
            ServerCommandSource source,
            String configId,
            String folder,
            String comment) {
        KnotLinkRequest request = KnotLinkRequest.command("BACKUP")
                .conversation()
                .field("config_id", configId)
                .field("folder", folder);
        if (comment != null && !comment.isBlank()) {
            request.field("comment", comment);
        }
        return execute(source, request, response -> sendSuccessMessage(source, response));
    }

    private static int executeRestoreTarget(
            ServerCommandSource source,
            String configId,
            String folder,
            String file) {
        if (rejectDedicatedRestore(source)) {
            return 0;
        }
        KnotLinkRequest request = KnotLinkRequest.command("RESTORE")
                .conversation()
                .field("config_id", configId)
                .field("folder", folder)
                .field("file", file);
        return execute(source, request, response -> sendSuccessMessage(source, response));
    }

    private static int executeListConfigs(ServerCommandSource source) {
        return execute(source, KnotLinkRequest.command("LIST_CONFIGS"), response -> {
            MutableText result = Text.translatable("minebackup.message.list_configs.title");
            String data = response.data();
            if (data == null || data.isBlank()) {
                result.append(Text.translatable("minebackup.message.list.empty"));
            } else {
                for (String item : CommandSuggestions.splitList(data)) {
                    String[] parts = item.split(",", 2);
                    if (parts.length == 2) {
                        result.append(Text.translatable(
                                "minebackup.message.list_configs.entry",
                                parts[0],
                                parts[1]));
                    }
                }
            }
            source.sendFeedback(() -> result, false);
        });
    }

    private static int executeListFolders(ServerCommandSource source, String configId) {
        KnotLinkRequest request = KnotLinkRequest.command("LIST_FOLDERS")
                .field("config_id", configId);
        return execute(source, request, response -> {
            MutableText result = Text.translatable("minebackup.message.list_folders.title", configId);
            List<String> folders = CommandSuggestions.splitList(response.data());
            if (folders.isEmpty()) {
                result.append(Text.translatable("minebackup.message.list.empty"));
            } else {
                for (int index = 0; index < folders.size(); index++) {
                    result.append(Text.translatable(
                            "minebackup.message.list_folders.entry",
                            index,
                            folders.get(index)));
                }
            }
            source.sendFeedback(() -> result, false);
        });
    }

    private static int executeListBackups(ServerCommandSource source, String configId, String folder) {
        KnotLinkRequest request = KnotLinkRequest.command("LIST_BACKUPS")
                .field("config_id", configId)
                .field("folder", folder);
        return execute(source, request, response -> {
            MutableText result = Text.translatable(
                    "minebackup.message.list_backups.title",
                    configId,
                    folder);
            List<String> backups = CommandSuggestions.splitList(response.data());
            if (backups.isEmpty()) {
                result.append(Text.translatable("minebackup.message.list.empty"));
            } else {
                for (String backup : backups) {
                    result.append(Text.translatable("minebackup.message.list_backups.entry", backup));
                }
            }
            source.sendFeedback(() -> result, false);
        });
    }

    private static int executeRestoreConfirm(ServerCommandSource source) {
        RestoreControlResult result = MineBackup.confirmPendingRestore();
        if (result == RestoreControlResult.CONFIRMED) {
            return 1;
        }
        source.sendError(Text.translatable("minebackup.message.restore.countdown.not_pending"));
        return 0;
    }

    private static int executeRestoreStop(ServerCommandSource source) {
        RestoreControlResult result = MineBackup.cancelPendingRestore();
        if (result == RestoreControlResult.CANCELLED) {
            return 1;
        }
        source.sendError(Text.translatable("minebackup.message.restore.countdown.not_pending"));
        return 0;
    }

    private static int executeAutoStart(ServerCommandSource source, int minutes) {
        AutoBackupUpdateResult result = MineBackup.startAutomaticBackup(Duration.ofMinutes(minutes));
        if (!result.success()) {
            sendAutoFailure(source, result);
            return 0;
        }
        source.sendFeedback(
                () -> Text.translatable("minebackup.message.auto.started", minutes),
                true);
        return 1;
    }

    private static int executeAutoStop(ServerCommandSource source) {
        AutoBackupUpdateResult result = MineBackup.stopAutomaticBackup();
        if (!result.success()) {
            sendAutoFailure(source, result);
            return 0;
        }
        source.sendFeedback(
                () -> Text.translatable("minebackup.message.auto.stopped"),
                true);
        return 1;
    }

    private static void observeBackup(
            ServerCommandSource source,
            OperationHandle<BackupResult> handle) {
        handle.completion().thenAccept(result -> source.getServer().execute(() -> {
            if (result.outcome() == BackupResult.Outcome.NO_CHANGES) {
                source.sendFeedback(
                        () -> Text.translatable("minebackup.message.backup.no_changes"),
                        false);
            } else if (result.outcome() == BackupResult.Outcome.REJECTED
                    || result.outcome() == BackupResult.Outcome.FAILED) {
                sendOperationFailure(source, result.failure());
            }
        }));
    }

    private static void observeRestore(
            ServerCommandSource source,
            OperationHandle<RestoreResult> handle) {
        handle.completion().thenAccept(result -> source.getServer().execute(() -> {
            if (result.outcome() == RestoreResult.Outcome.REJECTED
                    || result.outcome() == RestoreResult.Outcome.FAILED) {
                sendOperationFailure(source, result.failure());
            }
        }));
    }

    private static void sendAutoFailure(ServerCommandSource source, AutoBackupUpdateResult result) {
        sendOperationFailure(source, result.failure());
    }

    private static void sendOperationFailure(
            ServerCommandSource source,
            Optional<OperationFailure> failure) {
        String message = failure.map(OperationFailure::message).orElse("Unknown operation failure");
        source.sendError(Text.translatable(
                "minebackup.message.command.fail",
                Text.literal(message)));
    }

    private static String callerId(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        return player == null
                ? "minebackup:console"
                : "minebackup:player:" + player.getGameProfile().getId();
    }

    private static int execute(
            ServerCommandSource source,
            KnotLinkRequest request,
            Consumer<KnotLinkResponse> onSuccess) {
        source.sendFeedback(
                () -> Text.translatable("minebackup.message.command.sent", request.commandName()),
                false);
        MineBackup.knotLink().query(request).whenComplete((response, error) ->
                source.getServer().execute(() -> {
                    if (error != null) {
                        MineBackup.LOGGER.warn("KnotLink command {} failed", request.commandName(), error);
                        source.sendError(Text.translatable("minebackup.message.communication_failed"));
                        return;
                    }
                    if (!response.isOk()) {
                        source.sendError(Text.translatable(
                                "minebackup.message.command.fail",
                                Text.literal(response.displayMessage())));
                        return;
                    }
                    onSuccess.accept(response);
                }));
        return 1;
    }

    private static void sendSuccessMessage(ServerCommandSource source, KnotLinkResponse response) {
        source.sendFeedback(
                () -> Text.translatable(
                        "minebackup.message.command.response",
                        Text.literal(response.displayMessage())),
                false);
    }

    private static int sendHelp(ServerCommandSource source) {
        source.sendFeedback(CommandHelpRegistry::buildRootHelp, false);
        return 1;
    }

    private static boolean saveCurrentWorld(ServerCommandSource source) {
        source.sendFeedback(() -> Text.translatable("minebackup.message.save.start"), false);
        if (LocalSaveCoordinator.save(source.getServer())) {
            source.sendFeedback(() -> Text.translatable("minebackup.message.save.success"), false);
            return true;
        }
        source.sendError(Text.translatable("minebackup.message.save.fail"));
        return false;
    }

    private static boolean rejectDedicatedRestore(ServerCommandSource source) {
        if (!source.getServer().isDedicated()) {
            return false;
        }
        source.sendError(Text.translatable("minebackup.message.restore.unsupported_dedicated"));
        return true;
    }

    private static void warnAboutVoxy(ServerCommandSource source) {
        if (Files.isDirectory(source.getServer().getSavePath(WorldSavePath.ROOT).resolve("voxy"))) {
            source.sendMessage(Text.translatable("minebackup.message.command.voxy_may_cause_issues"));
        }
    }

    private static boolean hasCommandAccess(ServerCommandSource source) {
        MinecraftServer server = source.getServer();
        if (server == null) {
            // MinecraftClient uses a server-less, no-permission source while compiling
            // command trees for the client. Returning false marks this node as
            // restricted without dereferencing an unavailable server.
            return false;
        }
        if (server.isDedicated()) {
            return source.hasPermissionLevel(2);
        }
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            return false;
        }
        GameProfile owner = server.getHostProfile();
        return owner != null && owner.getId().equals(player.getGameProfile().getId());
    }

}
