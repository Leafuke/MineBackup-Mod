package com.leafuke.minebackup;

import com.leafuke.minebackup.knotlink.OpenSocketQuerier;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.TranslatableComponent;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class Command {

    // 与 C++ KnotLink Responser 匹配的ID
    private static final String QUERIER_APP_ID = "0x00000020";
    private static final String QUERIER_SOCKET_ID = "0x00000010";

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {

        dispatcher.register(Commands.literal("minebackup")
                .requires(src -> src.hasPermission(2)) // 需要OP权限

                // 1. 本地保存指令
                .then(Commands.literal("save")
                        .executes(ctx -> {
                            CommandSourceStack source = ctx.getSource();
                            MinecraftServer server = source.getServer();
                            source.sendSuccess(new TranslatableComponent("minebackup.message.save.start"), true);
                            for (ServerLevel level : server.getAllLevels()) {
                                level.save(null, true, false);
                            }
                            source.sendSuccess(new TranslatableComponent("minebackup.message.save.success"), true);
                            return 1;
                        })
                )

                // 2. 查询配置列表
                .then(Commands.literal("list_configs")
                        .executes(ctx -> {
                            ctx.getSource().sendSuccess(new TranslatableComponent("minebackup.message.list_configs.start"), false);
                            queryBackend("LIST_CONFIGS", response -> handleListConfigsResponse(ctx.getSource(), response));
                            return 1;
                        })
                )

                // 3. (修正) 列出指定配置中的所有世界
                .then(Commands.literal("list_worlds")
                        .then(Commands.argument("config_id", IntegerArgumentType.integer())
                                .executes(ctx -> {
                                    int configId = IntegerArgumentType.getInteger(ctx, "config_id");
                                    ctx.getSource().sendSuccess(new TranslatableComponent("minebackup.message.list_worlds.start", String.valueOf(configId)), false);
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
                                            ctx.getSource().sendSuccess(new TranslatableComponent("minebackup.message.list_backups.start", String.valueOf(configId), String.valueOf(worldIndex)), false);
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
                            source.sendSuccess(new TranslatableComponent("minebackup.message.save.start"), true);
                            for (ServerLevel level : server.getAllLevels()) {
                                level.save(null, true, false);
                            }
                            source.sendSuccess(new TranslatableComponent("minebackup.message.save.success"), true);
                            // 修正：在1.18.2中，一个executes不能链式调用另一个，需要分开或者合并逻辑
                            executeRemoteCommand(source, "BACKUP_CURRENT");
                            return 1;
                        })
                        .then(Commands.argument("comment", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    CommandSourceStack source = ctx.getSource();
                                    MinecraftServer server = source.getServer();
                                    source.sendSuccess(new TranslatableComponent("minebackup.message.save.start"), true);
                                    for (ServerLevel level : server.getAllLevels()) {
                                        level.save(null, true, false);
                                    }
                                    source.sendSuccess(new TranslatableComponent("minebackup.message.save.success"), true);
                                    executeRemoteCommand(source, String.format("BACKUP_CURRENT %s", StringArgumentType.getString(ctx, "comment")));
                                    return 1;
                                })
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
        );
    }

    // 统一的查询后端方法
    private static void queryBackend(String command, Consumer<String> callback) {
        OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, command).thenAccept(callback);
    }

    // 统一处理需要通用响应的远程命令
    private static int executeRemoteCommand(CommandSourceStack source, String command) {
        // 使用带参数的翻译键
        source.sendSuccess(new TranslatableComponent("minebackup.message.command.sent", command), false);
        queryBackend(command, response -> {
            source.getServer().execute(() -> {
                if (response != null && response.startsWith("ERROR:")) {
                    source.sendFailure(new TranslatableComponent("minebackup.message.command.fail", response.substring(6)));
                } else {
                    source.sendSuccess(new TranslatableComponent("minebackup.message.command.response", response), false);
                }
            });
        });
        return 1;
    }

    // 处理 LIST_CONFIGS 的响应
    private static void handleListConfigsResponse(CommandSourceStack source, String response) {
        source.getServer().execute(() -> {
            if (response == null || !response.startsWith("OK:")) {
                String error = response != null ? response : "No response";
                source.sendFailure(new TranslatableComponent("minebackup.message.list_configs.fail", error));
                return;
            }
            // MutableComponent 已被废弃，直接使用 Component 或其子类
            // 注意：因为 entry 带有换行符，我们不需要手动添加
            var resultText = new TranslatableComponent("minebackup.message.list_configs.success.title");
            String data = response.substring(3);
            if (data.isEmpty()) {
                resultText.append(new TranslatableComponent("minebackup.message.list_configs.empty"));
            } else {
                for (String config : data.split(";")) {
                    String[] parts = config.split(",", 2);
                    if (parts.length == 2) {
                        resultText.append(new TranslatableComponent("minebackup.message.list_configs.success.entry", parts[0], parts[1]));
                    }
                }
            }
            source.sendSuccess(resultText, false);
        });
    }

    // 处理 LIST_WORLDS 的响应
    private static void handleListWorldsResponse(CommandSourceStack source, String response, int configId) {
        source.getServer().execute(() -> {
            if (response == null || !response.startsWith("OK:")) {
                source.sendFailure(new TranslatableComponent("minebackup.message.list_worlds.fail", response != null ? response : "No response"));
                return;
            }
            MutableComponent resultText = new TranslatableComponent("minebackup.message.list_worlds.success.title", String.valueOf(configId));
            String data = response.substring(3);
            if (data.isEmpty()) {
                resultText.append(new TranslatableComponent("minebackup.message.list_worlds.empty"));
            } else {
                String[] worlds = data.split(";");
                for (int i = 0; i < worlds.length; i++) {
                    resultText.append(new TranslatableComponent("minebackup.message.list_worlds.success.entry", String.valueOf(i), worlds[i]));
                }
            }
            source.sendSuccess(resultText, false);
        });
    }

    // 处理 LIST_BACKUPS 的响应
    private static void handleListBackupsResponse(CommandSourceStack source, String response, int configId, int worldIndex) {
        source.getServer().execute(() -> {
            if (response == null || !response.startsWith("OK:")) {
                source.sendFailure(new TranslatableComponent("minebackup.message.list_backups.fail", response != null ? response : "No response"));
                return;
            }
            MutableComponent resultText = new TranslatableComponent("minebackup.message.list_backups.success.title", String.valueOf(configId), String.valueOf(worldIndex));
            String data = response.substring(3);
            if (data.isEmpty()) {
                resultText.append(new TranslatableComponent("minebackup.message.list_backups.empty"));
            } else {
                for (String file : data.split(";")) {
                    if (!file.isEmpty()) {
                        resultText.append(new TranslatableComponent("minebackup.message.list_backups.success.entry", file));
                    }
                }
            }
            source.sendSuccess(resultText, false);
        });
    }


    // 为 "restore" 指令提供备份文件名的自动补全建议
    private static CompletableFuture<Suggestions> suggestBackupFiles(int configId, int worldIndex, SuggestionsBuilder builder) {
        // 调用正确的 LIST_BACKUPS 命令
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

