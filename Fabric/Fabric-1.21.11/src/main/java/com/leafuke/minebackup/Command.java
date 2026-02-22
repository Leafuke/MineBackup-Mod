package com.leafuke.minebackup;

import com.leafuke.minebackup.knotlink.OpenSocketQuerier;
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
import net.minecraft.server.permissions.Permissions;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * MineBackup 命令注册类（Fabric 1.21.11+，使用 Mojang 官方映射）
 * 提供与 MineBackup 主程序交互的各种命令
 */
public class Command {

    // KnotLink 通信 ID
    private static final String QUERIER_APP_ID = "0x00000020";
    private static final String QUERIER_SOCKET_ID = "0x00000010";

    /**
     * 注册所有 MineBackup 命令
     * @param dispatcher 命令分发器
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {

        dispatcher.register(Commands.literal("mb")
                .requires(src -> {
                    // CommandSourceStack 可能在命令树构建时缺少 server（避免 NPE）
                    MinecraftServer server = src.getServer();
                    if (server == null) return false;
                    // 单人游戏允许所有人使用，服务器需要OP权限
                    if (!server.isDedicatedServer()) return true;
                    return src.permissions().hasPermission(Permissions.COMMANDS_MODERATOR);
                })

                // 1. 本地保存指令
                .then(Commands.literal("save")
                        .executes(ctx -> {
                            saveAllWorlds(ctx.getSource());
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

                // 3. 列出指定配置中的所有世界
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

                // 6. 执行一次远程还原
                .then(Commands.literal("restore")
                        .then(Commands.argument("config_id", IntegerArgumentType.integer())
                                .then(Commands.argument("world_index", IntegerArgumentType.integer())
                                        .then(Commands.argument("backup_file", StringArgumentType.string())
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

                // 7. 快速保存并备份当前世界
                .then(Commands.literal("quicksave")
                        .executes(ctx -> {
                            CommandSourceStack source = ctx.getSource();
                            saveAllWorlds(source);
                            return executeRemoteCommand(source, "BACKUP_CURRENT");
                        })
                        .then(Commands.argument("comment", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    CommandSourceStack source = ctx.getSource();
                                    saveAllWorlds(source);
                                    return executeRemoteCommand(source,
                                            String.format("BACKUP_CURRENT %s", StringArgumentType.getString(ctx, "comment")));
                                })
                        )
                )

                // 8. 启动远程自动备份
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

                // 9. 停止远程自动备份
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

                // 10. 与 WorldEdit 快照联动
                .then(Commands.literal("snap")
                        .then(Commands.argument("config_id", IntegerArgumentType.integer())
                                .then(Commands.argument("world_index", IntegerArgumentType.integer())
                                        .then(Commands.argument("backup_file", StringArgumentType.string())
                                                .suggests((ctx, builder) -> suggestBackupFiles(
                                                        IntegerArgumentType.getInteger(ctx, "config_id"),
                                                        IntegerArgumentType.getInteger(ctx, "world_index"),
                                                        builder))
                                                .executes(ctx -> {
                                                    String command = String.format("ADD_TO_WE %d %d %s",
                                                            IntegerArgumentType.getInteger(ctx, "config_id"),
                                                            IntegerArgumentType.getInteger(ctx, "world_index"),
                                                            StringArgumentType.getString(ctx, "backup_file"));
                                                    ctx.getSource().sendSuccess(() -> Component.translatable("minebackup.message.snap.sent", command), false);
                                                    queryBackend(command, response -> handleGenericResponse(ctx.getSource(), response, "snap"));
                                                    return 1;
                                                })
                                        )
                                )
                        )
                )
        );

        // 旧命令入口：提示已迁移到 /mb
        dispatcher.register(Commands.literal("minebackup")
                .requires(src -> {
                    MinecraftServer server = src.getServer();
                    if (server == null) return false;
                    if (!server.isDedicatedServer()) return true;
                    return src.permissions().hasPermission(Permissions.COMMANDS_MODERATOR);
                })
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.translatable("minebackup.message.command.migrated"), false);
                    return 1;
                })
                .then(Commands.argument("args", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            ctx.getSource().sendSuccess(() -> Component.translatable("minebackup.message.command.migrated"), false);
                            return 1;
                        })
                )
        );
    }

    /**
     * 向后端发送查询请求
     * @param command 命令字符串
     * @param callback 响应回调
     */
    private static void queryBackend(String command, java.util.function.Consumer<String> callback) {
        // 增强健壮性：处理可能返回 null 的 future 与异常
        CompletableFuture<String> future = OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, command);
        if (future == null) {
            // 直接回调 null，由上层处理为无响应
            try {
                callback.accept(null);
            } catch (Exception ignored) {}
            return;
        }
        future
            .exceptionally(ex -> {
                MineBackup.LOGGER.error("与 MineBackup 主程序通信异常: {}", ex.getMessage());
                return "ERROR:COMMUNICATION_FAILED";
            })
            .thenAccept(resp -> {
                try {
                    callback.accept(resp);
                } catch (Exception e) {
                    MineBackup.LOGGER.error("处理后端响应时发生异常: {}", e.getMessage());
                }
            });
    }

    /**
     * 执行本地所有维度落盘，保证远程备份前数据尽量一致。
     */
    private static void saveAllWorlds(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        source.sendSuccess(() -> Component.translatable("minebackup.message.save.start"), true);
        for (ServerLevel level : server.getAllLevels()) {
            level.save(null, true, false);
        }
        source.sendSuccess(() -> Component.translatable("minebackup.message.save.success"), true);
    }

    /**
     * 通用响应处理器
     * @param source 命令来源
     * @param response 响应内容
     * @param commandType 命令类型
     */
    private static void handleGenericResponse(CommandSourceStack source, String response, String commandType) {
        source.getServer().execute(() -> {
            if (response != null && response.startsWith("ERROR:")) {
                source.sendFailure(Component.translatable("minebackup.message.command.fail", localizeErrorDetail(response)));
            } else {
                source.sendSuccess(() -> Component.translatable("minebackup.message." + commandType + ".response", response), false);
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

    /**
     * 执行远程命令
     * @param source 命令来源
     * @param command 命令字符串
     * @return 命令执行结果
     */

    private static int executeRemoteCommand(CommandSourceStack source, String command) {
        if (command == null || command.trim().isEmpty()) {
            source.sendFailure(Component.translatable("minebackup.message.command.invalid"));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("minebackup.message.command.sent", command), false);
        String commandType = command.split(" ")[0].toLowerCase();
        queryBackend(command, response -> handleGenericResponse(source, response, commandType));
        return 1;
    }

    /**
     * 处理 LIST_CONFIGS 响应
     */
    private static void handleListConfigsResponse(CommandSourceStack source, String response) {
        source.getServer().execute(() -> {
            if (response == null || !response.startsWith("OK:")) {
                Object errorDetail = localizeErrorDetail(response);
                source.sendFailure(Component.translatable("minebackup.message.list_configs.fail", errorDetail));
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

    /**
     * 处理 LIST_WORLDS 响应
     */
    private static void handleListWorldsResponse(CommandSourceStack source, String response, int configId) {
        source.getServer().execute(() -> {
            if (response == null || !response.startsWith("OK:")) {
                Object errorDetail = localizeErrorDetail(response);
                source.sendFailure(Component.translatable("minebackup.message.list_worlds.fail", errorDetail));
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

    /**
     * 处理 LIST_BACKUPS 响应
     */
    private static void handleListBackupsResponse(CommandSourceStack source, String response, int configId, int worldIndex) {
        source.getServer().execute(() -> {
            if (response == null || !response.startsWith("OK:")) {
                Object errorDetail = localizeErrorDetail(response);
                source.sendFailure(Component.translatable("minebackup.message.list_backups.fail", errorDetail));
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

    /**
     * 为还原命令提供备份文件名的自动补全建议
     */
    private static CompletableFuture<Suggestions> suggestBackupFiles(int configId, int worldIndex, SuggestionsBuilder builder) {
        String command = String.format("LIST_BACKUPS %d %d", configId, worldIndex);
        return OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, command)
                .thenApply(response -> {
                    if (response != null && response.startsWith("OK:")) {
                        String data = response.substring(3);
                        String[] files = data.split(";");
                        String remaining = builder.getRemaining();
                        String remLower = remaining == null ? "" : remaining.toLowerCase(Locale.ROOT);
                        for (String file : files) {
                            if (!file.isEmpty()) {
                                // 不要强制加单引号，直接建议文件名
                                if (file.toLowerCase(Locale.ROOT).startsWith(remLower)) {
                                    builder.suggest(file);
                                }
                            }
                        }
                    }
                    return builder.build();
                })
                .exceptionally(ex -> {
                    // 出现异常时返回空建议（避免抛出）
                    MineBackup.LOGGER.warn("获取备份文件补全失败: {}", ex.getMessage());
                    return builder.build();
                });
    }
}

