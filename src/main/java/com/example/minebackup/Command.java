package com.example.minebackup;

import com.example.minebackup.knotlink.OpenSocketQuerier;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.concurrent.CompletableFuture;

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
                            source.sendSuccess(() -> Component.literal("§6[MineBackup] §e正在执行本地世界保存..."), true);
                            for (ServerLevel level : server.getAllLevels()) {
                                level.save(null, true, false);
                            }
                            source.sendSuccess(() -> Component.literal("§a[MineBackup] §e本地世界保存成功。"), true);
                            return 1;
                        })
                )

                // 2. 查询配置列表
                .then(Commands.literal("list_configs")
                        .executes(ctx -> {
                            ctx.getSource().sendSuccess(() -> Component.literal("§e正在从 MineBackup 获取配置列表..."), false);
                            queryBackend("LIST_CONFIGS", response -> handleListConfigsResponse(ctx.getSource(), response));
                            return 1;
                        })
                )

                // 3. (修正) 列出指定配置中的所有世界
                .then(Commands.literal("list_worlds")
                        .then(Commands.argument("config_id", IntegerArgumentType.integer())
                                .executes(ctx -> {
                                    int configId = IntegerArgumentType.getInteger(ctx, "config_id");
                                    ctx.getSource().sendSuccess(() -> Component.literal(String.format("§e正在获取配置 %d 的世界列表...", configId)), false);
                                    queryBackend(
                                            String.format("LIST_WORLDS %d", configId),
                                            response -> handleListWorldsResponse(ctx.getSource(), response, configId)
                                    );
                                    return 1;
                                })
                        )
                )

                // 4. (新增) 列出指定世界的所有备份文件
                .then(Commands.literal("list_backups")
                        .then(Commands.argument("config_id", IntegerArgumentType.integer())
                                .then(Commands.argument("world_index", IntegerArgumentType.integer())
                                        .executes(ctx -> {
                                            int configId = IntegerArgumentType.getInteger(ctx, "config_id");
                                            int worldIndex = IntegerArgumentType.getInteger(ctx, "world_index");
                                            ctx.getSource().sendSuccess(() -> Component.literal(String.format("§e正在获取配置 %d, 世界 %d 的备份列表...", configId, worldIndex)), false);
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
        );
    }

    // 统一的查询后端方法
    private static void queryBackend(String command, java.util.function.Consumer<String> callback) {
        OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, command).thenAccept(callback);
    }

    // 统一处理需要通用响应的远程命令
    private static int executeRemoteCommand(CommandSourceStack source, String command) {
        source.sendSuccess(() -> Component.literal("§e向 MineBackup 发送指令: §f" + command), false);
        queryBackend(command, response -> {
            source.getServer().execute(() -> {
                if (response != null && response.startsWith("ERROR:")) {
                    source.sendFailure(Component.literal("§c指令失败: " + response.substring(6)));
                } else {
                    // 成功消息由广播事件处理，这里只显示通用响应
                    source.sendSuccess(() -> Component.literal("§aMineBackup 响应: §f" + response), false);
                }
            });
        });
        return 1;
    }

    // 处理 LIST_CONFIGS 的响应
    private static void handleListConfigsResponse(CommandSourceStack source, String response) {
        source.getServer().execute(() -> {
            if (response == null || !response.startsWith("OK:")) {
                source.sendFailure(Component.literal("§c获取配置失败: " + (response != null ? response : "无响应")));
                return;
            }
            MutableComponent resultText = Component.literal("§a可用配置列表:\n");
            String data = response.substring(3);
            if (data.isEmpty()) {
                resultText.append(Component.literal("§7(无可用配置)"));
            } else {
                for (String config : data.split(";")) {
                    String[] parts = config.split(",", 2);
                    if (parts.length == 2) {
                        resultText.append(Component.literal(String.format("§f - ID: §b%s§f, 名称: §d%s\n", parts[0], parts[1])));
                    }
                }
            }
            source.sendSuccess(() -> resultText, false);
        });
    }

    // (新增) 处理 LIST_WORLDS 的响应
    private static void handleListWorldsResponse(CommandSourceStack source, String response, int configId) {
        source.getServer().execute(() -> {
            if (response == null || !response.startsWith("OK:")) {
                source.sendFailure(Component.literal("§c获取世界列表失败: " + (response != null ? response : "无响应")));
                return;
            }
            MutableComponent resultText = Component.literal(String.format("§a配置 %d 的世界列表:\n", configId));
            String data = response.substring(3);
            if (data.isEmpty()) {
                resultText.append(Component.literal("§7(该配置下无世界)"));
            } else {
                String[] worlds = data.split(";");
                for (int i = 0; i < worlds.length; i++) {
                    resultText.append(Component.literal(String.format("§f - 索引: §b%d§f, 名称: §d%s\n", i, worlds[i])));
                }
            }
            source.sendSuccess(() -> resultText, false);
        });
    }

    // (新增) 处理 LIST_BACKUPS 的响应
    private static void handleListBackupsResponse(CommandSourceStack source, String response, int configId, int worldIndex) {
        source.getServer().execute(() -> {
            if (response == null || !response.startsWith("OK:")) {
                source.sendFailure(Component.literal("§c获取备份列表失败: " + (response != null ? response : "无响应")));
                return;
            }
            MutableComponent resultText = Component.literal(String.format("§a配置 %d, 世界 %d 的备份列表:\n", configId, worldIndex));
            String data = response.substring(3);
            if (data.isEmpty()) {
                resultText.append(Component.literal("§7(该世界暂无备份)"));
            } else {
                for (String file : data.split(";")) {
                    if (!file.isEmpty()) {
                        resultText.append(Component.literal("§f - §b" + file + "\n"));
                    }
                }
            }
            source.sendSuccess(() -> resultText, false);
        });
    }


    // (修正) 为 "restore" 指令提供备份文件名的自动补全建议
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