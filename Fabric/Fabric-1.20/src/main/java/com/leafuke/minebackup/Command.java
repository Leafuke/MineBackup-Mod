package com.leafuke.minebackup;

import com.leafuke.minebackup.knotlink.OpenSocketQuerier;
import com.leafuke.minebackup.knotlink.SignalSender;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

import java.util.concurrent.CompletableFuture;

public class Command {

    private static final String QUERIER_APP_ID = "0x00000020";
    private static final String QUERIER_SOCKET_ID = "0x00000010";

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(net.minecraft.server.command.CommandManager.literal("minebackup")
                .requires(src -> {
                    if (!src.getServer().isDedicated()) return true;
                    return src.hasPermissionLevel(2);
                })
                .then(net.minecraft.server.command.CommandManager.literal("save")
                        .executes((CommandContext<ServerCommandSource> ctx) -> {
                            ServerCommandSource source = ctx.getSource();
                            MinecraftServer server = source.getServer();
                            source.sendFeedback(() -> Text.translatable("minebackup.message.save.start"), true);
                            for (ServerWorld world : server.getWorlds()) {
                                world.save(null, true, false);
                            }
                            source.sendFeedback(() -> Text.translatable("minebackup.message.save.success"), true);
                            return 1;
                        })
                )
                .then(net.minecraft.server.command.CommandManager.literal("list_configs")
                        .executes((CommandContext<ServerCommandSource> ctx) -> {
                            ctx.getSource().sendFeedback(() -> Text.translatable("minebackup.message.list_configs.start"), false);
                            queryBackend("LIST_CONFIGS", response -> handleListConfigsResponse(ctx.getSource(), response));
                            return 1;
                        })
                )
                .then(net.minecraft.server.command.CommandManager.literal("list_worlds")
                        .then(net.minecraft.server.command.CommandManager.argument("config_id", IntegerArgumentType.integer())
                                .executes((CommandContext<ServerCommandSource> ctx) -> {
                                    int configId = IntegerArgumentType.getInteger(ctx, "config_id");
                                    ctx.getSource().sendFeedback(() -> Text.translatable("minebackup.message.list_worlds.start", String.valueOf(configId)), false);
                                    queryBackend(
                                            String.format("LIST_WORLDS %d", configId),
                                            response -> handleListWorldsResponse(ctx.getSource(), response, configId)
                                    );
                                    return 1;
                                })
                        )
                )
                .then(net.minecraft.server.command.CommandManager.literal("list_backups")
                        .then(net.minecraft.server.command.CommandManager.argument("config_id", IntegerArgumentType.integer())
                                .then(net.minecraft.server.command.CommandManager.argument("world_index", IntegerArgumentType.integer())
                                        .executes((CommandContext<ServerCommandSource> ctx) -> {
                                            int configId = IntegerArgumentType.getInteger(ctx, "config_id");
                                            int worldIndex = IntegerArgumentType.getInteger(ctx, "world_index");
                                            ctx.getSource().sendFeedback(() -> Text.translatable("minebackup.message.list_backups.start", String.valueOf(configId), String.valueOf(worldIndex)), false);
                                            queryBackend(
                                                    String.format("LIST_BACKUPS %d %d", configId, worldIndex),
                                                    response -> handleListBackupsResponse(ctx.getSource(), response, configId, worldIndex)
                                            );
                                            return 1;
                                        })
                                )
                        )
                )
                .then(net.minecraft.server.command.CommandManager.literal("backup")
                        .then(net.minecraft.server.command.CommandManager.argument("config_id", IntegerArgumentType.integer())
                                .then(net.minecraft.server.command.CommandManager.argument("world_index", IntegerArgumentType.integer())
                                        .executes(ctx -> executeRemoteCommand(ctx.getSource(),
                                                String.format("BACKUP %d %d",
                                                        IntegerArgumentType.getInteger(ctx, "config_id"),
                                                        IntegerArgumentType.getInteger(ctx, "world_index"))))
                                        .then(net.minecraft.server.command.CommandManager.argument("comment", StringArgumentType.greedyString())
                                                .executes(ctx -> executeRemoteCommand(ctx.getSource(),
                                                        String.format("BACKUP %d %d %s",
                                                                IntegerArgumentType.getInteger(ctx, "config_id"),
                                                                IntegerArgumentType.getInteger(ctx, "world_index"),
                                                                StringArgumentType.getString(ctx, "comment"))))
                                        )
                                )
                        )
                )
                .then(net.minecraft.server.command.CommandManager.literal("restore")
                        .then(net.minecraft.server.command.CommandManager.argument("config_id", IntegerArgumentType.integer())
                                .then(net.minecraft.server.command.CommandManager.argument("world_index", IntegerArgumentType.integer())
                                        .then(net.minecraft.server.command.CommandManager.argument("backup_file", StringArgumentType.string())
                                                .suggests((ctx, builder) -> suggestBackupFiles(
                                                        IntegerArgumentType.getInteger(ctx, "config_id"),
                                                        IntegerArgumentType.getInteger(ctx, "world_index"),
                                                        builder))
                                                .executes(ctx -> executeRemoteCommand(ctx.getSource(),
                                                        String.format("RESTORE %d %d %s",
                                                                IntegerArgumentType.getInteger(ctx, "config_id"),
                                                                IntegerArgumentType.getInteger(ctx, "world_index"),
                                                                StringArgumentType.getString(ctx, "backup_file"))))
                                        )
                                )
                        )
                )
                .then(net.minecraft.server.command.CommandManager.literal("quicksave")
                        .executes(ctx -> {
                            ServerCommandSource source = ctx.getSource();
                            MinecraftServer server = source.getServer();
                            source.sendFeedback(() -> Text.translatable("minebackup.message.save.start"), true);
                            for (ServerWorld world : server.getWorlds()) {
                                world.save(null, true, false);
                            }
                            source.sendFeedback(() -> Text.translatable("minebackup.message.save.success"), true);
                            return 1;
                        })
                        .executes(ctx -> executeRemoteCommand(ctx.getSource(), "BACKUP_CURRENT"))
                        .then(net.minecraft.server.command.CommandManager.argument("comment", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    ServerCommandSource source = ctx.getSource();
                                    MinecraftServer server = source.getServer();
                                    source.sendFeedback(() -> Text.translatable("minebackup.message.save.start"), true);
                                    for (ServerWorld world : server.getWorlds()) {
                                        world.save(null, true, false);
                                    }
                                    source.sendFeedback(() -> Text.translatable("minebackup.message.save.success"), true);
                                    return 1;
                                })
                                .executes(ctx -> executeRemoteCommand(ctx.getSource(),
                                        String.format("BACKUP_CURRENT %s", StringArgumentType.getString(ctx, "comment"))))
                        )
                )
                .then(net.minecraft.server.command.CommandManager.literal("auto")
                        .then(net.minecraft.server.command.CommandManager.argument("config_id", IntegerArgumentType.integer())
                                .then(net.minecraft.server.command.CommandManager.argument("world_index", IntegerArgumentType.integer())
                                        .then(net.minecraft.server.command.CommandManager.argument("internal_time", IntegerArgumentType.integer())
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
                .then(net.minecraft.server.command.CommandManager.literal("stop")
                        .then(net.minecraft.server.command.CommandManager.argument("config_id", IntegerArgumentType.integer())
                                .then(net.minecraft.server.command.CommandManager.argument("world_index", IntegerArgumentType.integer())
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
                .then(net.minecraft.server.command.CommandManager.literal("snap")
                        .then(net.minecraft.server.command.CommandManager.argument("config_id", IntegerArgumentType.integer())
                                .then(net.minecraft.server.command.CommandManager.argument("world_index", IntegerArgumentType.integer())
                                        .then(net.minecraft.server.command.CommandManager.argument("backup_file", StringArgumentType.string())
                                                .suggests((ctx, builder) -> suggestBackupFiles(
                                                        IntegerArgumentType.getInteger(ctx, "config_id"),
                                                        IntegerArgumentType.getInteger(ctx, "world_index"),
                                                        builder))
                                                .executes(ctx -> {
                                                    String command = String.format("ADD_TO_WE %d %d %s",
                                                            IntegerArgumentType.getInteger(ctx, "config_id"),
                                                            IntegerArgumentType.getInteger(ctx, "world_index"),
                                                            StringArgumentType.getString(ctx, "backup_file"));
                                                    ctx.getSource().sendFeedback(() -> Text.translatable("minebackup.message.snap.sent", command), false);
                                                    queryBackend(command, response -> handleGenericResponse(ctx.getSource(), response, "snap"));
                                                    return 1;
                                                })
                                        )
                                )
                        )
                )
        );
    }

    private static void queryBackend(String command, java.util.function.Consumer<String> callback) {
        OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, command).thenAccept(callback);
    }

    private static void handleGenericResponse(ServerCommandSource source, String response, String commandType) {
        source.getServer().execute(() -> {
            if (response != null && response.startsWith("ERROR:")) {
                source.sendError(Text.translatable("minebackup.message.command.fail", response.substring(6)));
            } else {
                source.sendFeedback(() -> Text.translatable("minebackup.message." + commandType + ".response", response), false);
            }
        });
    }

    private static int executeRemoteCommand(ServerCommandSource source, String command) {
        source.sendFeedback(() -> Text.translatable("minebackup.message.command.sent", command), false);
        String commandType = command.split(" ")[0].toLowerCase();
        queryBackend(command, response -> handleGenericResponse(source, response, commandType));
        return 1;
    }

    private static void handleListConfigsResponse(ServerCommandSource source, String response) {
        source.getServer().execute(() -> {
            if (response == null || !response.startsWith("OK:")) {
                source.sendError(Text.translatable("minebackup.message.list_configs.fail", response != null ? response : "无响应"));
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
                source.sendError(Text.translatable("minebackup.message.list_worlds.fail", response != null ? response : "无响应"));
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
                source.sendError(Text.translatable("minebackup.message.list_backups.fail", response != null ? response : "无响应"));
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
                        String data = response.substring(3);
                        String[] files = data.split(";");
                        for (String file : files) {
                            if (!file.isEmpty() && file.toLowerCase().startsWith(builder.getRemaining().toLowerCase())) {
                                builder.suggest("'" + file + "'");
                            }
                        }
                    }
                    return builder.build();
                });
    }
}
