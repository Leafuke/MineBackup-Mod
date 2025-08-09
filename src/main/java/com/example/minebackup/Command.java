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

                            source.sendSuccess(() -> Component.literal("[MineBackup] 正在执行本地保存..."), true);

                            // 使用您验证过有效的保存方式
                            for (ServerLevel level : server.getAllLevels()) {
                                level.save(null, true, false);
                            }

                            source.sendSuccess(() -> Component.literal("[MineBackup] 本地保存成功。"), true);
                            return 1;
                        })
                )

                // 2. 查询配置列表
                .then(Commands.literal("list_configs")
                        .executes(ctx -> {
                            ctx.getSource().sendSuccess(() -> Component.literal("§e正在从 MineBackup 获取配置列表..."), false);
                            OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, "LIST_CONFIGS")
                                    .thenAccept(response -> handleConfigListResponse(ctx.getSource(), response));
                            return 1;
                        })
                )

                // 3. 触发一次远程备份
                .then(Commands.literal("backup")
                        .then(Commands.argument("config_id", IntegerArgumentType.integer())
                                .then(Commands.argument("world_index", IntegerArgumentType.integer())
                                        .executes(ctx -> { // 不带评论的备份
                                            return executeRemoteCommand(ctx.getSource(),
                                                    String.format("BACKUP %d %d",
                                                            IntegerArgumentType.getInteger(ctx, "config_id"),
                                                            IntegerArgumentType.getInteger(ctx, "world_index")));
                                        })
                                        .then(Commands.argument("comment", StringArgumentType.greedyString())
                                                .executes(ctx -> { // 带评论的备份
                                                    return executeRemoteCommand(ctx.getSource(),
                                                            String.format("BACKUP %d %d %s",
                                                                    IntegerArgumentType.getInteger(ctx, "config_id"),
                                                                    IntegerArgumentType.getInteger(ctx, "world_index"),
                                                                    StringArgumentType.getString(ctx, "comment")));
                                                })
                                        )))
                )

                // 4. 执行一次远程还原
                .then(Commands.literal("restore")
                        .then(Commands.argument("config_id", IntegerArgumentType.integer())
                                .then(Commands.argument("world_index", IntegerArgumentType.integer())
                                        .then(Commands.argument("backup_file", StringArgumentType.string())
                                                // 为备份文件名添加自动补全功能
                                                .suggests((ctx, builder) -> suggestBackupFiles(ctx.getSource(), builder,
                                                        IntegerArgumentType.getInteger(ctx, "config_id"),
                                                        IntegerArgumentType.getInteger(ctx, "world_index")))
                                                .executes(ctx -> executeRemoteCommand(ctx.getSource(),
                                                        String.format("RESTORE %d %d %s",
                                                                IntegerArgumentType.getInteger(ctx, "config_id"),
                                                                IntegerArgumentType.getInteger(ctx, "world_index"),
                                                                StringArgumentType.getString(ctx, "backup_file"))))
                                        )))
                )
        );
    }

    // 统一处理远程命令的执行和响应
    private static int executeRemoteCommand(CommandSourceStack source, String command) {
        source.sendSuccess(() -> Component.literal("§e向 MineBackup 发送指令: " + command), false);
        OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, command)
                .thenAccept(response -> {
                    source.getServer().execute(() -> {
                        if (response.startsWith("ERROR:")) {
                            source.sendFailure(Component.literal("§c指令失败: " + response.substring(6)));
                        } else {
                            source.sendSuccess(() -> Component.literal("§aMineBackup 响应: " + response), false);
                        }
                    });
                });
        return 1;
    }

    // 专门处理 LIST_CONFIGS 的响应格式
    private static void handleConfigListResponse(CommandSourceStack source, String response) {
        source.getServer().execute(() -> {
            if (response == null || response.startsWith("ERROR:")) {
                source.sendFailure(Component.literal("§c获取配置失败: " + (response != null ? response : "无响应")));
                return;
            }
            if (response.startsWith("OK:")) {
                MutableComponent resultText = Component.literal("§a可用配置列表:\n");
                String data = response.substring(3);
                if (data.isEmpty()) {
                    resultText.append(Component.literal("§7(无可用配置)"));
                } else {
                    String[] configs = data.split(";");
                    for (String config : configs) {
                        String[] parts = config.split(",", 2);
                        if (parts.length == 2) {
                            resultText.append(Component.literal(String.format("§f - ID: §b%s§f, 名称: §d%s\n", parts[0], parts[1])));
                        }
                    }
                }
                source.sendSuccess(() -> resultText, false);
            } else {
                source.sendFailure(Component.literal("§c收到了未知响应: " + response));
            }
        });
    }

    // 为 "restore" 指令提供备份文件名的自动补全建议
    private static CompletableFuture<Suggestions> suggestBackupFiles(CommandSourceStack source, SuggestionsBuilder builder, int configId, int worldIndex) {
        String command = String.format("LIST_WORLDS %d %d", configId, worldIndex);
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