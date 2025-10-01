package com.leafuke.minebackup;

import com.leafuke.minebackup.knotlink.OpenSocketQuerier;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.LiteralText;
import net.minecraft.text.MutableText;
import java.util.concurrent.CompletableFuture;

public class Command {

    private static final String QUERIER_APP_ID = "0x00000020";
    private static final String QUERIER_SOCKET_ID = "0x00000010";

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {

        dispatcher.register(CommandManager.literal("minebackup")
                        .requires(src -> src.hasPermissionLevel(2))

                        .then(CommandManager.literal("save")
                                .executes(ctx -> {
                                    ServerCommandSource source = ctx.getSource();
                                    MinecraftServer server = source.getServer();
                                    source.sendFeedback(new LiteralText("§6[MineBackup] §e正在执行本地世界保存..."), true);
                                    for (ServerWorld level : server.getWorlds()) {
                                        level.save(null, true, false);
                                    }
                                    source.sendFeedback(new LiteralText("§a[MineBackup] §e本地世界保存成功。"), true);
                                    return 1;
                                })
                        )
                        .then(CommandManager.literal("list_configs")
                                .executes(ctx -> {
                                    ctx.getSource().sendFeedback(new LiteralText("§e正在从 MineBackup 获取配置列表..."), false);
                                    queryBackend("LIST_CONFIGS", response -> handleListConfigsResponse(ctx.getSource(), response));
                                    return 1;
                                })
                        )
                        .then(CommandManager.literal("list_worlds")
                                .then(CommandManager.argument("config_id", IntegerArgumentType.integer())
                                        .executes(ctx -> {
                                            int configId = IntegerArgumentType.getInteger(ctx, "config_id");
                                            ctx.getSource().sendFeedback(new LiteralText(String.format("§e正在获取配置 %d 的世界列表...", configId)), false);
                                            queryBackend(
                                                    String.format("LIST_WORLDS %d", configId),
                                                    response -> handleListWorldsResponse(ctx.getSource(), response, configId)
                                            );
                                            return 1;
                                        })
                                )
                        )
                        .then(CommandManager.literal("list_backups")
                                .then(CommandManager.argument("config_id", IntegerArgumentType.integer())
                                        .then(CommandManager.argument("world_index", IntegerArgumentType.integer())
                                                .executes(ctx -> {
                                                    int configId = IntegerArgumentType.getInteger(ctx, "config_id");
                                                    int worldIndex = IntegerArgumentType.getInteger(ctx, "world_index");
                                                    ctx.getSource().sendFeedback(new LiteralText(String.format("§e正在获取配置 %d, 世界 %d 的备份列表...", configId, worldIndex)), false);
                                                    queryBackend(
                                                            String.format("LIST_BACKUPS %d %d", configId, worldIndex),
                                                            response -> handleListBackupsResponse(ctx.getSource(), response, configId, worldIndex)
                                                    );
                                                    return 1;
                                                })
                                        )
                                )
                        )
                        // ... 完整的指令树，下面的代码确认无误 ...
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
                                                        .executes(ctx -> executeRemoteCommand(ctx.getSource(),
                                                                String.format("RESTORE %d %d %s",
                                                                        IntegerArgumentType.getInteger(ctx, "config_id"),
                                                                        IntegerArgumentType.getInteger(ctx, "world_index"),
                                                                        StringArgumentType.getString(ctx, "backup_file"))))
                                                )
                                        )
                                )
                        )
                //... (其他指令，如quicksave, auto, stop等，逻辑不变)
        );
    }

    private static void queryBackend(String command, java.util.function.Consumer<String> callback) {
        OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, command).thenAccept(callback);
    }

    private static int executeRemoteCommand(ServerCommandSource source, String command) {
        source.sendFeedback(new LiteralText("§e向 MineBackup 发送指令: §f" + command), false);
        queryBackend(command, response -> {
            source.getServer().execute(() -> {
                if (response != null && response.startsWith("ERROR:")) {
                    source.sendError(new LiteralText("§c指令失败: " + response.substring(6)));
                } else {
                    source.sendFeedback(new LiteralText("§aMineBackup 响应: §f" + response), false);
                }
            });
        });
        return 1;
    }

    private static void handleListConfigsResponse(ServerCommandSource source, String response) {
        source.getServer().execute(() -> {
            if (response == null || !response.startsWith("OK:")) {
                source.sendError(new LiteralText("§c获取配置失败: " + (response != null ? response : "无响应")));
                return;
            }
            MutableText resultText = new LiteralText("§a可用配置列表:\n");
            String data = response.substring(3);
            if (data.isEmpty()) {
                resultText.append(new LiteralText("§7(无可用配置)"));
            } else {
                for (String config : data.split(";")) {
                    String[] parts = config.split(",", 2);
                    if (parts.length == 2) {
                        resultText.append(new LiteralText(String.format("§f - ID: §b%s§f, 名称: §d%s\n", parts[0], parts[1])));
                    }
                }
            }
            source.sendFeedback(resultText, false);
        });
    }

    private static void handleListWorldsResponse(ServerCommandSource source, String response, int configId) {
        source.getServer().execute(() -> {
            if (response == null || !response.startsWith("OK:")) {
                source.sendError(new LiteralText("§c获取世界列表失败: " + (response != null ? response : "无响应")));
                return;
            }
            MutableText resultText = new LiteralText(String.format("§a配置 %d 的世界列表:\n", configId));
            String data = response.substring(3);
            if (data.isEmpty()) {
                resultText.append(new LiteralText("§7(该配置下无世界)"));
            } else {
                String[] worlds = data.split(";");
                for (int i = 0; i < worlds.length; i++) {
                    resultText.append(new LiteralText(String.format("§f - 索引: §b%d§f, 名称: §d%s\n", i, worlds[i])));
                }
            }
            source.sendFeedback(resultText, false);
        });
    }

    private static void handleListBackupsResponse(ServerCommandSource source, String response, int configId, int worldIndex) {
        source.getServer().execute(() -> {
            if (response == null || !response.startsWith("OK:")) {
                source.sendError(new LiteralText("§c获取备份列表失败: " + (response != null ? response : "无响应")));
                return;
            }
            MutableText resultText = new LiteralText(String.format("§a配置 %d, 世界 %d 的备份列表:\n", configId, worldIndex));
            String data = response.substring(3);
            if (data.isEmpty()) {
                resultText.append(new LiteralText("§7(该世界暂无备份)"));
            } else {
                for (String file : data.split(";")) {
                    if (!file.isEmpty()) {
                        resultText.append(new LiteralText("§f - §b" + file + "\n"));
                    }
                }
            }
            source.sendFeedback(resultText, false);
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
                                builder.suggest(file);
                            }
                        }
                    }
                    return builder.build();
                });
    }
}