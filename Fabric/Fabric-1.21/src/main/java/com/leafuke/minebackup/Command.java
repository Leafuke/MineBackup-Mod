package com.leafuke.minebackup;

import com.leafuke.minebackup.knotlink.OpenSocketQuerier;
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

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * MineBackup 命令注册类（Fabric 1.21，使用 Yarn 映射）
 * 提供与 MineBackup 主程序交互的各种命令
 */
public class Command {

    // KnotLink 通信 ID
    private static final String QUERIER_APP_ID = "0x00000020";
    private static final String QUERIER_SOCKET_ID = "0x00000010";
    private static final long CURRENT_BACKUPS_QUERY_INTERVAL_MS = 5000L;
    private static volatile long lastCurrentBackupsQueryAtMs = 0L;
    private static volatile String lastCurrentBackupsResponse = null;
    private static CompletableFuture<String> currentBackupsQueryFuture = null;

    /**
     * 注册所有 MineBackup 命令
     * @param dispatcher 命令分发器
     */
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(net.minecraft.server.command.CommandManager.literal("mb")
                .requires(src -> {
                    // CommandSourceStack 可能在命令树构建时缺少 server（避免 NPE）
                    MinecraftServer server = src.getServer();
                    if (server == null) return false;
                    // 单人游戏允许所有人使用，服务器需要OP权限
                    if (!server.isDedicated()) return true;
                    return src.hasPermissionLevel(2);
                })

                // 1. 本地保存指令
                .then(net.minecraft.server.command.CommandManager.literal("save")
                        .executes((CommandContext<ServerCommandSource> ctx) -> {
                            // 仅执行本地落盘，不触发远程备份。
                            saveAllWorlds(ctx.getSource());
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
                            // 先执行本地保存，再请求后端进行当前世界备份。
                            saveAllWorlds(ctx.getSource());
                            return executeRemoteCommand(ctx.getSource(), "BACKUP_CURRENT");
                        })
                        .then(net.minecraft.server.command.CommandManager.argument("comment", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    saveAllWorlds(ctx.getSource());
                                    return executeRemoteCommand(ctx.getSource(),
                                            String.format("BACKUP_CURRENT %s", StringArgumentType.getString(ctx, "comment")));
                                })
                        )
                )
                        .then(net.minecraft.server.command.CommandManager.literal("quickrestore")
                            .executes(ctx -> executeRemoteCommand(ctx.getSource(), "RESTORE_CURRENT_LATEST"))
                            .then(net.minecraft.server.command.CommandManager.argument("backup_file", StringArgumentType.string())
                                .suggests((ctx, builder) -> suggestCurrentBackupFiles(builder))
                                .executes(ctx -> executeRemoteCommand(ctx.getSource(),
                                    String.format("RESTORE_CURRENT %s", StringArgumentType.getString(ctx, "backup_file"))))
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

        // 旧命令入口：提示已迁移到 /mb
        dispatcher.register(net.minecraft.server.command.CommandManager.literal("minebackup")
                .requires(src -> {
                    MinecraftServer server = src.getServer();
                    if (server == null) return false;
                    if (!server.isDedicated()) return true;
                    return src.hasPermissionLevel(2);
                })
                .executes(ctx -> {
                    ctx.getSource().sendFeedback(() -> Text.translatable("minebackup.message.command.migrated"), false);
                    return 1;
                })
                .then(net.minecraft.server.command.CommandManager.argument("args", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            ctx.getSource().sendFeedback(() -> Text.translatable("minebackup.message.command.migrated"), false);
                            return 1;
                        })
                )
        );
    }

    private static void queryBackend(String command, java.util.function.Consumer<String> callback) {
        // 统一处理通信异常，避免异步异常丢失。
        CompletableFuture<String> future = OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, command);
        if (future == null) {
            callback.accept(null);
            return;
        }
        future
                .exceptionally(ex -> {
                    MineBackup.LOGGER.error("与 MineBackup 主程序通信异常: {}", ex.getMessage());
                    return "ERROR:COMMUNICATION_FAILED";
                })
                .thenAccept(response -> {
                    try {
                        callback.accept(response);
                    } catch (Exception e) {
                        MineBackup.LOGGER.error("处理后端响应时发生异常: {}", e.getMessage());
                    }
                });
    }

    /**
     * 执行本地所有维度落盘，保证热备份前区块与元数据尽量一致。
     */
    private static void saveAllWorlds(ServerCommandSource source) {
        MinecraftServer server = source.getServer();
        source.sendFeedback(() -> Text.translatable("minebackup.message.save.start"), true);
        for (ServerWorld world : server.getWorlds()) {
            world.save(null, true, false);
        }
        source.sendFeedback(() -> Text.translatable("minebackup.message.save.success"), true);
    }

    private static void handleGenericResponse(ServerCommandSource source, String response, String commandType) {
        source.getServer().execute(() -> {
            if (response != null && response.startsWith("ERROR:")) {
                source.sendError(Text.translatable("minebackup.message.command.fail", localizeErrorDetail(response)));
            } else {
                source.sendFeedback(() -> Text.translatable("minebackup.message." + commandType + ".response", response), false);
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
        source.sendFeedback(() -> Text.translatable("minebackup.message.command.sent", command), false);
        String commandType = command.split(" ")[0].toLowerCase();
        queryBackend(command, response -> handleGenericResponse(source, response, commandType));
        return 1;
    }

    private static void handleListConfigsResponse(ServerCommandSource source, String response) {
        source.getServer().execute(() -> {
            if (response == null || !response.startsWith("OK:")) {
                Object errorDetail = localizeErrorDetail(response);
                source.sendError(Text.translatable("minebackup.message.list_configs.fail", errorDetail));
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
                Object errorDetail = localizeErrorDetail(response);
                source.sendError(Text.translatable("minebackup.message.list_worlds.fail", errorDetail));
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
                Object errorDetail = localizeErrorDetail(response);
                source.sendError(Text.translatable("minebackup.message.list_backups.fail", errorDetail));
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
                        String remaining = builder.getRemaining();
                        String remLower = remaining == null ? "" : remaining.toLowerCase(Locale.ROOT);
                        for (String file : files) {
                            if (!file.isEmpty() && file.toLowerCase(Locale.ROOT).startsWith(remLower)) {
                                // 直接建议原始文件名，避免强制加引号影响命令输入体验。
                                builder.suggest(file);
                            }
                        }
                    }
                    return builder.build();
                })
                .exceptionally(ex -> {
                    MineBackup.LOGGER.warn("获取备份文件补全失败: {}", ex.getMessage());
                    return builder.build();
                });
    }

    private static CompletableFuture<Suggestions> suggestCurrentBackupFiles(SuggestionsBuilder builder) {
        return queryCurrentBackupsThrottled()
                .thenApply(response -> {
                    if (response != null && response.startsWith("OK:")) {
                        String data = response.substring(3);
                        String[] files = data.split(";");
                        String remaining = builder.getRemaining();
                        String normalized = remaining == null ? "" : remaining;
                        if (!normalized.isEmpty() && (normalized.charAt(0) == '\'' || normalized.charAt(0) == '"')) {
                            normalized = normalized.substring(1);
                        }
                        String remLower = normalized.toLowerCase(Locale.ROOT);
                        for (String file : files) {
                            if (!file.isEmpty() && file.toLowerCase(Locale.ROOT).startsWith(remLower)) {
                                builder.suggest("'" + file.replace("'", "\\'") + "'");
                            }
                        }
                    }
                    return builder.build();
                })
                .exceptionally(ex -> {
                    MineBackup.LOGGER.warn("获取当前世界备份补全失败: {}", ex.getMessage());
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
                    MineBackup.LOGGER.warn("查询当前世界备份失败: {}", ex.getMessage());
                    return lastCurrentBackupsResponse;
                }
                return response;
            });
            return currentBackupsQueryFuture;
        }
    }

}
