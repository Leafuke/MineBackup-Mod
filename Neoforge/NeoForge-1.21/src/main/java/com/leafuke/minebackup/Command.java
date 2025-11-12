package com.leafuke.minebackup;

import com.leafuke.minebackup.knotlink.OpenSocketQuerier;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.concurrent.CompletableFuture;

public class Command {

    private static final String QUERIER_APP_ID = "0x00000020";
    private static final String QUERIER_SOCKET_ID = "0x00000010";

    // 注册方法的签名已更新以匹配 NeoForge 的 RegisterCommandsEvent
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context, Commands.CommandSelection selection) {

        // 内部的所有命令定义 (dispatcher.register(...)) 都是基于 Brigadier 的，无需更改。
        // ... (你的所有命令定义代码保持不变)
        dispatcher.register(Commands.literal("minebackup")
                .requires(src -> src.hasPermission(2)) // 需要OP权限

                // 1. 本地保存指令
                .then(Commands.literal("save")
                        .executes(ctx -> {
                            CommandSourceStack source = ctx.getSource();
                            MinecraftServer server = source.getServer();
                            source.sendSuccess(() -> Component.translatable("minebackup.message.save.start"), true);
                            for (ServerLevel level : server.getAllLevels()) {
                                level.save(null, true, false);
                            }
                            source.sendSuccess(() -> Component.translatable("minebackup.message.save.success"), true);
                            return 1;
                        })
                )

                // 2. 查询配置列表
                .then(Commands.literal("list_configs")
                        .executes(ctx -> {
                            ctx.getSource().sendSuccess(() -> Component.translatable("minebackup.message.list_configs.start"), false);
                            queryBackend("LIST_CONFIGS", response -> handleListConfigsResponse(ctx.getSource(), response));
                            return 1;
                        })
                )

                // 3. (修正) 列出指定配置中的所有世界
                .then(Commands.literal("list_worlds")
                        .then(Commands.argument("config_id", IntegerArgumentType.integer())
                                .executes(ctx -> {
                                    int configId = IntegerArgumentType.getInteger(ctx, "config_id");
                                    ctx.getSource().sendSuccess(() -> Component.translatable("minebackup.message.list_worlds.start", String.valueOf(configId)), false);
                                    queryBackend(
                                            String.format("LIST_WORLDS %d", configId),
                                            response -> handleListWorldsResponse(ctx.getSource(), response, configId)
                                    );
                                    return 1;
                                })
                        )
                )

                // 4. 列出指定世界的所有备份文件
                .then(Commands.literal("list_backups")
                        .then(Commands.argument("config_id", IntegerArgumentType.integer())
                                .then(Commands.argument("world_index", IntegerArgumentType.integer())
                                        .executes(ctx -> {
                                            int configId = IntegerArgumentType.getInteger(ctx, "config_id");
                                            int worldIndex = IntegerArgumentType.getInteger(ctx, "world_index");
                                            ctx.getSource().sendSuccess(() -> Component.translatable("minebackup.message.list_backups.start", String.valueOf(configId), String.valueOf(worldIndex)), false);
                                            queryBackend(
                                                    String.format("LIST_BACKUPS %d %d", configId, worldIndex),
                                                    response -> handleListBackupsResponse(ctx.getSource(), response, configId, worldIndex)
                                            );
                                            return 1;
                                        })
                                )
                        )
                )

                // 5. 触发一次远程备份
                .then(Commands.literal("backup")
                        .then(Commands.argument("config_id", IntegerArgumentType.integer())
                                .then(Commands.argument("world_index", IntegerArgumentType.integer())
                                        .executes(ctx -> executeRemoteCommand(ctx.getSource(), // 不带评论
                                                String.format("BACKUP %d %d",
                                                        IntegerArgumentType.getInteger(ctx, "config_id"),
                                                        IntegerArgumentType.getInteger(ctx, "world_index"))))
                                        .then(Commands.argument("comment", StringArgumentType.greedyString())
                                                .executes(ctx -> executeRemoteCommand(ctx.getSource(), // 带评论
                                                        String.format("BACKUP %d %d %s",
                                                                IntegerArgumentType.getInteger(ctx, "config_id"),
                                                                IntegerArgumentType.getInteger(ctx, "world_index"),
                                                                StringArgumentType.getString(ctx, "comment"))))
                                        )
                                )
                        )
                )

                // 6. (修正自动补全) 执行一次远程还原
                .then(Commands.literal("restore")
                        .then(Commands.argument("config_id", IntegerArgumentType.integer())
                                .then(Commands.argument("world_index", IntegerArgumentType.integer())
                                        .then(Commands.argument("backup_file", StringArgumentType.string())
                                                .suggests((ctx, builder) -> suggestBackupFiles( // 自动补全
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

                // 7. 执行被封当前存档的操作
                .then(Commands.literal("quicksave")
                        .executes(ctx -> {
                            CommandSourceStack source = ctx.getSource();
                            MinecraftServer server = source.getServer();
                            source.sendSuccess(() -> Component.translatable("minebackup.message.save.start"), true);
                            for (ServerLevel level : server.getAllLevels()) {
                                level.save(null, true, false);
                            }
                            source.sendSuccess(() -> Component.translatable("minebackup.message.save.success"), true);
                            return 1;
                        })
                        .executes(ctx ->executeRemoteCommand(ctx.getSource(), // 不带评论
                                "BACKUP_CURRENT"))
                        .then(Commands.argument("comment", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    CommandSourceStack source = ctx.getSource();
                                    MinecraftServer server = source.getServer();
                                    source.sendSuccess(() -> Component.translatable("minebackup.message.save.start"), true);
                                    for (ServerLevel level : server.getAllLevels()) {
                                        level.save(null, true, false);
                                    }
                                    source.sendSuccess(() -> Component.translatable("minebackup.message.save.success"), true);
                                    return 1;
                                })
                                .executes(ctx -> executeRemoteCommand(ctx.getSource(), // 带评论
                                        String.format("BACKUP_CURRENT %s",
                                                StringArgumentType.getString(ctx, "comment"))))
                        )
                )

                // 8. 启动远程自动备份
                .then(Commands.literal("auto")
                        .then(Commands.argument("config_id", IntegerArgumentType.integer())
                                .then(Commands.argument("world_index", IntegerArgumentType.integer())
                                        .then(Commands.argument("internal_time", IntegerArgumentType.integer())
                                                .executes(ctx -> executeRemoteCommand(ctx.getSource(),
                                                        String.format("AUTO_BACKUP %d %d %d",
                                                                IntegerArgumentType.getInteger(ctx, "config_id"),
                                                                IntegerArgumentType.getInteger(ctx, "world_index"),
                                                                IntegerArgumentType.getInteger(ctx, "internal_time"))))
                                        )
                                )
                        )
                )

                // 9. 停止远程自动备份
                .then(Commands.literal("stop")
                        .then(Commands.argument("config_id", IntegerArgumentType.integer())
                                .then(Commands.argument("world_index", IntegerArgumentType.integer())
                                        .executes(ctx -> executeRemoteCommand(ctx.getSource(),
                                                String.format("STOP_AUTO_BACKUP %d %d",
                                                        IntegerArgumentType.getInteger(ctx, "config_id"),
                                                        IntegerArgumentType.getInteger(ctx, "world_index"))))
                                )
                        )
                )

                // 10. 与WorldEdit快照联动
                .then(Commands.literal("snap")
                        .then(Commands.argument("config_id", IntegerArgumentType.integer())
                                .then(Commands.argument("world_index", IntegerArgumentType.integer())
                                        .then(Commands.argument("backup_file", StringArgumentType.string())
                                                .suggests((ctx, builder) -> suggestBackupFiles( // 复用备份文件自动补全
                                                        IntegerArgumentType.getInteger(ctx, "config_id"),
                                                        IntegerArgumentType.getInteger(ctx, "world_index"),
                                                        builder))
                                                .executes(ctx -> {
                                                    // 构造发送给后端的命令
                                                    String command = String.format("ADD_TO_WE %d %d %s",
                                                            IntegerArgumentType.getInteger(ctx, "config_id"),
                                                            IntegerArgumentType.getInteger(ctx, "world_index"),
                                                            StringArgumentType.getString(ctx, "backup_file"));
                                                    // 发送指令并处理响应
                                                    ctx.getSource().sendSuccess(() -> Component.translatable("minebackup.message.snap.sent", command), false);
                                                    queryBackend(command, response -> handleGenericResponse(ctx.getSource(), response, "snap"));
                                                    return 1;
                                                })
                                        )
                                )
                        )
                )
        );
    }

    // ----- 其余所有辅助方法都无需修改 -----

    private static void queryBackend(String command, java.util.function.Consumer<String> callback) {
        OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, command).thenAccept(callback);
    }

    private static void handleGenericResponse(CommandSourceStack source, String response, String commandType) {
        source.getServer().execute(() -> {
            if (response != null && response.startsWith("ERROR:")) {
                source.sendFailure(Component.translatable("minebackup.message.command.fail", response.substring(6)));
            } else {
                source.sendSuccess(() -> Component.translatable("minebackup.message." + commandType + ".response", response), false);
            }
        });
    }

    private static int executeRemoteCommand(CommandSourceStack source, String command) {
        source.sendSuccess(() -> Component.translatable("minebackup.message.command.sent", command), false);
        String commandType = command.split(" ")[0].toLowerCase();
        queryBackend(command, response -> handleGenericResponse(source, response, commandType));
        return 1;
    }

    private static void handleListConfigsResponse(CommandSourceStack source, String response) {
        source.getServer().execute(() -> {
            if (response == null || !response.startsWith("OK:")) {
                source.sendFailure(Component.translatable("minebackup.message.list_configs.fail", response != null ? response : "无响应"));
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
                source.sendFailure(Component.translatable("minebackup.message.list_worlds.fail", response != null ? response : "无响应"));
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
                source.sendFailure(Component.translatable("minebackup.message.list_backups.fail", response != null ? response : "无响应"));
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