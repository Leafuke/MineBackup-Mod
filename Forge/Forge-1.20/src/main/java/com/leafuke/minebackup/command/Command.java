package com.leafuke.minebackup.command;

import com.leafuke.minebackup.MineBackup;
import com.leafuke.minebackup.api.v2.BackupCatalogRequest;
import com.leafuke.minebackup.api.v2.BackupCatalogResult;
import com.leafuke.minebackup.api.v2.BackupEntry;
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
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public final class Command {
    private Command() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("mb")
                .requires(Command::hasCommandAccess)
                .executes(context -> sendHelp(context.getSource()))
                .then(Commands.literal("help")
                        .executes(context -> sendHelp(context.getSource()))
                        .then(Commands.argument("command", StringArgumentType.greedyString())
                                .suggests((context, builder) -> CommandHelpRegistry.suggestCommands(builder))
                                .executes(context -> {
                                    String command = StringArgumentType.getString(context, "command");
                                    context.getSource().sendSuccess(
                                            () -> CommandHelpRegistry.buildCommandHelp(command),
                                            false);
                                    return 1;
                                })))
                .then(Commands.literal("save")
                        .executes(context -> saveCurrentWorld(context.getSource()) ? 1 : 0))
                .then(Commands.literal("backup")
                        .executes(context -> executeBackupCurrent(context.getSource(), null))
                        .then(Commands.argument("comment", StringArgumentType.greedyString())
                                .executes(context -> executeBackupCurrent(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "comment")))))
                .then(Commands.literal("restore")
                        .executes(context -> executeRestoreCurrent(context.getSource(), null))
                        .then(Commands.argument("file", StringArgumentType.string())
                                .suggests((context, builder) -> CommandSuggestions.suggestCurrentBackups(builder))
                                .executes(context -> executeRestoreCurrent(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "file")))))
                .then(Commands.literal("confirm")
                        .executes(context -> executeRestoreConfirm(context.getSource())))
                .then(Commands.literal("stop")
                        .executes(context -> executeRestoreStop(context.getSource())))
                .then(buildTargetCommands())
                .then(buildListCommands())
                .then(buildAutoCommands()));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> buildTargetCommands() {
        return Commands.literal("target")
                .then(Commands.literal("backup")
                        .then(Commands.argument("config_id", StringArgumentType.word())
                                .suggests((context, builder) -> CommandSuggestions.suggestConfigIds(builder))
                                .then(Commands.argument("folder", StringArgumentType.string())
                                        .suggests((context, builder) -> CommandSuggestions.suggestFolders(
                                                StringArgumentType.getString(context, "config_id"),
                                                builder))
                                        .executes(context -> executeBackupTarget(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "config_id"),
                                                StringArgumentType.getString(context, "folder"),
                                                null))
                                        .then(Commands.argument("comment", StringArgumentType.greedyString())
                                                .executes(context -> executeBackupTarget(
                                                        context.getSource(),
                                                        StringArgumentType.getString(context, "config_id"),
                                                        StringArgumentType.getString(context, "folder"),
                                                        StringArgumentType.getString(context, "comment")))))))
                .then(Commands.literal("restore")
                        .then(Commands.argument("config_id", StringArgumentType.word())
                                .suggests((context, builder) -> CommandSuggestions.suggestConfigIds(builder))
                                .then(Commands.argument("folder", StringArgumentType.string())
                                        .suggests((context, builder) -> CommandSuggestions.suggestFolders(
                                                StringArgumentType.getString(context, "config_id"),
                                                builder))
                                        .then(Commands.argument("file", StringArgumentType.string())
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

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> buildListCommands() {
        return Commands.literal("list")
                .then(Commands.literal("configs")
                        .executes(context -> executeListConfigs(context.getSource())))
                .then(Commands.literal("folders")
                        .then(Commands.argument("config_id", StringArgumentType.word())
                                .suggests((context, builder) -> CommandSuggestions.suggestConfigIds(builder))
                                .executes(context -> executeListFolders(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "config_id")))))
                .then(Commands.literal("backups")
                        .executes(context -> executeListCurrentBackups(context.getSource(), 1))
                        .then(Commands.literal("current")
                                .executes(context -> executeListCurrentBackups(context.getSource(), 1))
                                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                        .executes(context -> executeListCurrentBackups(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "page")))))
                        .then(Commands.argument("config_id", StringArgumentType.word())
                                .suggests((context, builder) -> CommandSuggestions.suggestConfigIds(builder))
                                .then(Commands.argument("folder", StringArgumentType.string())
                                        .suggests((context, builder) -> CommandSuggestions.suggestFolders(
                                                StringArgumentType.getString(context, "config_id"),
                                                builder))
                                        .executes(context -> executeListBackups(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "config_id"),
                                                StringArgumentType.getString(context, "folder"))))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> buildAutoCommands() {
        return Commands.literal("auto")
                .then(Commands.literal("start")
                        .then(Commands.argument(
                                        "minutes",
                                        IntegerArgumentType.integer(
                                                1,
                                                Config.MAX_AUTO_BACKUP_INTERVAL_MINUTES))
                                .executes(context -> executeAutoStart(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "minutes")))))
                .then(Commands.literal("stop")
                        .executes(context -> executeAutoStop(context.getSource())));
    }

    private static int executeBackupCurrent(CommandSourceStack source, String comment) {
        OperationHandle<BackupResult> handle = MineBackup.api().backupCurrent(
                BackupRequest.create(callerId(source), comment));
        source.sendSuccess(
                () -> Component.translatable("minebackup.message.command.sent", "BACKUP"),
                false);
        observeBackup(source, handle);
        return handle.phase() == OperationPhase.REJECTED ? 0 : 1;
    }

    private static int executeRestoreCurrent(CommandSourceStack source, String file) {
        warnAboutVoxy(source);
        RestoreRequest request = file == null || file.isBlank()
                ? RestoreRequest.latest(callerId(source))
                : RestoreRequest.file(callerId(source), file);
        OperationHandle<RestoreResult> handle = MineBackup.api().restoreCurrent(request);
        observeRestore(source, handle);
        return handle.phase() == OperationPhase.REJECTED ? 0 : 1;
    }

    private static int executeBackupTarget(
            CommandSourceStack source,
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
            CommandSourceStack source,
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

    private static int executeListConfigs(CommandSourceStack source) {
        return execute(source, KnotLinkRequest.command("LIST_CONFIGS"), response -> {
            MutableComponent result = Component.translatable("minebackup.message.list_configs.title");
            String data = response.data();
            if (data == null || data.isBlank()) {
                result.append(Component.translatable("minebackup.message.list.empty"));
            } else {
                for (String item : CommandSuggestions.splitList(data)) {
                    String[] parts = item.split(",", 2);
                    if (parts.length == 2) {
                        result.append(Component.translatable(
                                "minebackup.message.list_configs.entry",
                                parts[0],
                                parts[1]));
                    }
                }
            }
            source.sendSuccess(() -> result, false);
        });
    }

    private static int executeListFolders(CommandSourceStack source, String configId) {
        KnotLinkRequest request = KnotLinkRequest.command("LIST_FOLDERS")
                .field("config_id", configId);
        return execute(source, request, response -> {
            MutableComponent result = Component.translatable("minebackup.message.list_folders.title", configId);
            List<String> folders = CommandSuggestions.splitList(response.data());
            if (folders.isEmpty()) {
                result.append(Component.translatable("minebackup.message.list.empty"));
            } else {
                for (int index = 0; index < folders.size(); index++) {
                    result.append(Component.translatable(
                            "minebackup.message.list_folders.entry",
                            index,
                            folders.get(index)));
                }
            }
            source.sendSuccess(() -> result, false);
        });
    }

    private static int executeListBackups(CommandSourceStack source, String configId, String folder) {
        KnotLinkRequest request = KnotLinkRequest.command("LIST_BACKUPS")
                .field("config_id", configId)
                .field("folder", folder);
        return execute(source, request, response -> {
            MutableComponent result = Component.translatable(
                    "minebackup.message.list_backups.title",
                    configId,
                    folder);
            List<String> backups = CommandSuggestions.splitList(response.data());
            if (backups.isEmpty()) {
                result.append(Component.translatable("minebackup.message.list.empty"));
            } else {
                for (String backup : backups) {
                    result.append(Component.translatable("minebackup.message.list_backups.entry", backup));
                }
            }
            source.sendSuccess(() -> result, false);
        });
    }

    private static int executeListCurrentBackups(CommandSourceStack source, int page) {
        MineBackup.api()
                .listCurrentBackups(BackupCatalogRequest.create(callerId(source)))
                .whenComplete((result, error) ->
                        source.getServer().executeIfPossible(() -> {
                            if (error != null) {
                                MineBackup.LOGGER.warn("Unable to list current-world backups", error);
                                source.sendFailure(
                                        Component.translatable("minebackup.message.communication_failed"));
                                return;
                            }
                            if (result.outcome() != BackupCatalogResult.Outcome.SUCCESS) {
                                sendOperationFailure(source, result.failure());
                                return;
                            }
                            MutableComponent message =
                                    buildCurrentBackupList(result.entries(), page);
                            source.sendSuccess(() -> message, false);
                        }));
        return 1;
    }

    private static MutableComponent buildCurrentBackupList(
            List<BackupEntry> entries,
            int requestedPage) {
        CurrentBackupListModel.Page page = CurrentBackupListModel.create(
                entries,
                requestedPage,
                ZoneId.systemDefault());
        MutableComponent result = Component.translatable(
                "minebackup.message.list_current_backups.title",
                page.currentPage(),
                page.totalPages(),
                page.totalEntries());

        if (page.rows().isEmpty()) {
            result.append(Component.translatable("minebackup.message.list.empty"));
        } else {
            for (CurrentBackupListModel.Row row : page.rows()) {
                result.append(Component.literal("\n §7- "));
                result.append(currentBackupLabel(row));
                result.append(Component.literal(" "));
                result.append(currentRestoreButton(row));
            }
        }

        result.append(Component.literal("\n "));
        result.append(pageLink(
                "minebackup.message.list_current_backups.previous",
                page.currentPage() - 1,
                page.hasPrevious()));
        result.append(Component.translatable(
                "minebackup.message.list_current_backups.page",
                page.currentPage(),
                page.totalPages()));
        result.append(pageLink(
                "minebackup.message.list_current_backups.next",
                page.currentPage() + 1,
                page.hasNext()));
        return result;
    }

    private static MutableComponent currentBackupLabel(CurrentBackupListModel.Row row) {
        MutableComponent label;
        if (row.timestamp().isEmpty()) {
            label = Component.translatable(
                    "minebackup.message.list_current_backups.legacy",
                    row.fileName());
        } else if (row.comment().isPresent()) {
            label = Component.translatable(
                    "minebackup.message.list_current_backups.entry",
                    row.timestamp().get(),
                    row.comment().get());
        } else {
            label = Component.translatable(
                    "minebackup.message.list_current_backups.entry_no_comment",
                    row.timestamp().get());
        }
        return label.withStyle(style -> style.withHoverEvent(new HoverEvent(
                HoverEvent.Action.SHOW_TEXT,
                Component.translatable(
                        "minebackup.message.list_current_backups.file_hover",
                        row.fileName()))));
    }

    private static MutableComponent currentRestoreButton(CurrentBackupListModel.Row row) {
        return Component.translatable("minebackup.message.list_current_backups.restore")
                .withStyle(style -> style
                        .withColor(ChatFormatting.GREEN)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, row.restoreCommand()))
                        .withHoverEvent(new HoverEvent(
                                HoverEvent.Action.SHOW_TEXT,
                                Component.translatable(
                                        "minebackup.message.list_current_backups.restore_hover",
                                        row.fileName()))));
    }

    private static MutableComponent pageLink(String translationKey, int targetPage, boolean enabled) {
        MutableComponent link = Component.translatable(translationKey);
        if (!enabled) {
            return link.withStyle(ChatFormatting.DARK_GRAY);
        }
        String command = "/mb list backups current " + targetPage;
        return link.withStyle(style -> style
                .withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(
                        HoverEvent.Action.SHOW_TEXT,
                        Component.translatable(translationKey))));
    }

    private static int executeRestoreConfirm(CommandSourceStack source) {
        RestoreControlResult result = MineBackup.confirmPendingRestore();
        if (result == RestoreControlResult.CONFIRMED) {
            return 1;
        }
        source.sendFailure(Component.translatable("minebackup.message.restore.countdown.not_pending"));
        return 0;
    }

    private static int executeRestoreStop(CommandSourceStack source) {
        RestoreControlResult result = MineBackup.cancelPendingRestore();
        if (result == RestoreControlResult.CANCELLED) {
            return 1;
        }
        source.sendFailure(Component.translatable("minebackup.message.restore.countdown.not_pending"));
        return 0;
    }

    private static int executeAutoStart(CommandSourceStack source, int minutes) {
        AutoBackupUpdateResult result = MineBackup.startAutomaticBackup(Duration.ofMinutes(minutes));
        if (!result.success()) {
            sendAutoFailure(source, result);
            return 0;
        }
        source.sendSuccess(
                () -> Component.translatable("minebackup.message.auto.started", minutes),
                true);
        return 1;
    }

    private static int executeAutoStop(CommandSourceStack source) {
        AutoBackupUpdateResult result = MineBackup.stopAutomaticBackup();
        if (!result.success()) {
            sendAutoFailure(source, result);
            return 0;
        }
        source.sendSuccess(
                () -> Component.translatable("minebackup.message.auto.stopped"),
                true);
        return 1;
    }

    private static void observeBackup(
            CommandSourceStack source,
            OperationHandle<BackupResult> handle) {
        handle.completion().thenAccept(result -> source.getServer().executeIfPossible(() -> {
            if (result.outcome() == BackupResult.Outcome.NO_CHANGES) {
                source.sendSuccess(
                        () -> Component.translatable("minebackup.message.backup.no_changes"),
                        false);
            } else if (result.outcome() == BackupResult.Outcome.REJECTED
                    || result.outcome() == BackupResult.Outcome.FAILED) {
                sendOperationFailure(source, result.failure());
            }
        }));
    }

    private static void observeRestore(
            CommandSourceStack source,
            OperationHandle<RestoreResult> handle) {
        handle.completion().thenAccept(result -> source.getServer().executeIfPossible(() -> {
            if (result.outcome() == RestoreResult.Outcome.REJECTED
                    || result.outcome() == RestoreResult.Outcome.FAILED) {
                sendOperationFailure(source, result.failure());
            }
        }));
    }

    private static void sendAutoFailure(CommandSourceStack source, AutoBackupUpdateResult result) {
        sendOperationFailure(source, result.failure());
    }

    private static void sendOperationFailure(
            CommandSourceStack source,
            Optional<OperationFailure> failure) {
        String message = failure.map(OperationFailure::message).orElse("Unknown operation failure");
        source.sendFailure(Component.translatable(
                "minebackup.message.command.fail",
                Component.literal(message)));
    }

    private static String callerId(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        return player == null
                ? "minebackup:console"
                : "minebackup:player:" + player.getGameProfile().getId();
    }

    private static int execute(
            CommandSourceStack source,
            KnotLinkRequest request,
            Consumer<KnotLinkResponse> onSuccess) {
        source.sendSuccess(
                () -> Component.translatable("minebackup.message.command.sent", request.commandName()),
                false);
        MineBackup.knotLink().query(request).whenComplete((response, error) ->
                source.getServer().executeIfPossible(() -> {
                    if (error != null) {
                        MineBackup.LOGGER.warn("KnotLink command {} failed", request.commandName(), error);
                        source.sendFailure(Component.translatable("minebackup.message.communication_failed"));
                        return;
                    }
                    if (!response.isOk()) {
                        source.sendFailure(Component.translatable(
                                "minebackup.message.command.fail",
                                Component.literal(response.displayMessage())));
                        return;
                    }
                    onSuccess.accept(response);
                }));
        return 1;
    }

    private static void sendSuccessMessage(CommandSourceStack source, KnotLinkResponse response) {
        source.sendSuccess(
                () -> Component.translatable(
                        "minebackup.message.command.response",
                        Component.literal(response.displayMessage())),
                false);
    }

    private static int sendHelp(CommandSourceStack source) {
        source.sendSuccess(CommandHelpRegistry::buildRootHelp, false);
        return 1;
    }

    private static boolean saveCurrentWorld(CommandSourceStack source) {
        source.sendSuccess(() -> Component.translatable("minebackup.message.save.start"), false);
        if (LocalSaveCoordinator.save(source.getServer())) {
            source.sendSuccess(() -> Component.translatable("minebackup.message.save.success"), false);
            return true;
        }
        source.sendFailure(Component.translatable("minebackup.message.save.fail"));
        return false;
    }

    private static boolean rejectDedicatedRestore(CommandSourceStack source) {
        if (!source.getServer().isDedicatedServer()) {
            return false;
        }
        source.sendFailure(Component.translatable("minebackup.message.restore.unsupported_dedicated"));
        return true;
    }

    private static void warnAboutVoxy(CommandSourceStack source) {
        if (Files.isDirectory(source.getServer().getWorldPath(LevelResource.ROOT).resolve("voxy"))) {
            source.sendSystemMessage(Component.translatable("minebackup.message.command.voxy_may_cause_issues"));
        }
    }

    private static boolean hasCommandAccess(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        if (server == null) {
            // Minecraft uses a server-less, no-permission source while compiling
            // command trees for the client. Returning false marks this node as
            // restricted without dereferencing an unavailable server.
            return false;
        }
        if (server.isDedicatedServer()) {
            return source.hasPermission(2);
        }
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return false;
        }
        GameProfile owner = server.getSingleplayerProfile();
        return owner != null && owner.getId().equals(player.getGameProfile().getId());
    }

}
