package com.Leafuke.minebackup;

import com.Leafuke.minebackup.OpenSocketQuerier;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.command.CommandSource;
import net.minecraft.command.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.StringTextComponent;

import java.util.concurrent.CompletableFuture;

public class Command {
    // Register commands. This is adapted for Forge/MC 1.16.5 command system.
    public static void register(CommandDispatcher<CommandSource> dispatcher) {
        dispatcher.register(Commands.literal("minebackup")
                        .then(Commands.literal("save")
                                .executes(ctx -> {
                                    CommandSource source = ctx.getSource();
                                    source.sendSuccess(new StringTextComponent("[MineBackup] 正在执行本地世界保存..."), true);
                                    // Save the world on server thread
                                    MinecraftServer server = source.getServer();
                                    server.execute(() -> {
                                        try {
                                            server.saveAllChunks(true, false, false);
                                            source.sendSuccess(new StringTextComponent("[MineBackup] 本地世界保存成功。"), true);
                                        } catch (Exception e) {
                                            source.sendFailure(new StringTextComponent("[MineBackup] 保存失败: " + e.getMessage()));
                                        }
                                    });
                                    return 1;
                                }))
                        .then(Commands.literal("list_configs")
                                .executes(ctx -> {
                                    CommandSource source = ctx.getSource();
                                    source.sendSuccess(new StringTextComponent("[MineBackup] 正在从 MineBackup 获取配置列表..."), false);
                                    OpenSocketQuerier.query(MineBackup.QUERIER_APP_ID, MineBackup.QUERIER_SOCKET_ID, "LIST_CONFIGS")
                                            .thenAccept(response -> {
                                                source.getServer().execute(() -> {
                                                    if (response == null) {
                                                        source.sendFailure(new StringTextComponent("[MineBackup] 无响应"));
                                                        return;
                                                    }
                                                    if (response.startsWith("OK:")) {
                                                        String data = response.substring(3);
                                                        if (data.isEmpty()) {
                                                            source.sendSuccess(new StringTextComponent("[MineBackup] 未找到配置。"), false);
                                                        } else {
                                                            String[] configs = data.split(";");
                                                            source.sendSuccess(new StringTextComponent("[MineBackup] 配置列表:"), false);
                                                            for (String c : configs) {
                                                                if (!c.isEmpty()) source.sendSuccess(new StringTextComponent(" - " + c), false);
                                                            }
                                                        }
                                                    } else {
                                                        source.sendFailure(new StringTextComponent("[MineBackup] 错误: " + response));
                                                    }
                                                });
                                            });
                                    return 1;
                                }))
                        .then(Commands.literal("list_worlds")
                                .then(Commands.argument("config_id", IntegerArgumentType.integer())
                                        .executes(ctx -> {
                                            int configId = IntegerArgumentType.getInteger(ctx, "config_id");
                                            CommandSource source = ctx.getSource();
                                            source.sendSuccess(new StringTextComponent(String.format("[MineBackup] 正在获取配置 %d 的世界列表...", configId)), false);
                                            OpenSocketQuerier.query(MineBackup.QUERIER_APP_ID, MineBackup.QUERIER_SOCKET_ID, "LIST_WORLDS " + configId)
                                                    .thenAccept(response -> {
                                                        source.getServer().execute(() -> {
                                                            if (response == null) {
                                                                source.sendFailure(new StringTextComponent("[MineBackup] 无响应"));
                                                                return;
                                                            }
                                                            if (response.startsWith("OK:")) {
                                                                String data = response.substring(3);
                                                                String[] worlds = data.split(";");
                                                                source.sendSuccess(new StringTextComponent("[MineBackup] 世界列表:"), false);
                                                                for (String w : worlds) {
                                                                    if (!w.isEmpty()) source.sendSuccess(new StringTextComponent(" - " + w), false);
                                                                }
                                                            } else {
                                                                source.sendFailure(new StringTextComponent("[MineBackup] 错误: " + response));
                                                            }
                                                        });
                                                    });
                                            return 1;
                                        })))
                .then(Commands.literal("list_backups")
                        .then(Commands.argument("config_id", IntegerArgumentType.integer())
                                .then(Commands.argument("world_index", IntegerArgumentType.integer())
                                        .executes(ctx -> {
                                            int configId = IntegerArgumentType.getInteger(ctx, "config_id");
                                            int worldIndex = IntegerArgumentType.getInteger(ctx, "world_index");
                                            CommandSource source = ctx.getSource();
                                            source.sendSuccess(new StringTextComponent("[MineBackup] 正在获取备份列表..."), false);
                                            OpenSocketQuerier.query(MineBackup.QUERIER_APP_ID, MineBackup.QUERIER_SOCKET_ID,
                                                            String.format("LIST_BACKUPS %d %d", configId, worldIndex))
                                                    .thenAccept(response -> {
                                                        source.getServer().execute(() -> {
                                                            if (response == null) {
                                                                source.sendFailure(new StringTextComponent("[MineBackup] 无响应"));
                                                                return;
                                                            }
                                                            if (response.startsWith("OK:")) {
                                                                String data = response.substring(3);
                                                                String[] files = data.split(";");
                                                                source.sendSuccess(new StringTextComponent("[MineBackup] 备份列表:"), false);
                                                                for (String f : files) {
                                                                    if (!f.isEmpty()) source.sendSuccess(new StringTextComponent(" - " + f), false);
                                                                }
                                                            } else {
                                                                source.sendFailure(new StringTextComponent("[MineBackup] 错误: " + response));
                                                            }
                                                        });
                                                    });
                                            return 1;
                                        }))))
                .then(Commands.literal("restore")
                        .then(Commands.argument("config_id", IntegerArgumentType.integer())
                                .then(Commands.argument("world_index", IntegerArgumentType.integer())
                                        .then(Commands.argument("filename", StringArgumentType.word())
                                                .suggests((ctx, builder) -> suggestBackupFiles(
                                                        IntegerArgumentType.getInteger(ctx, "config_id"),
                                                        IntegerArgumentType.getInteger(ctx, "world_index"),
                                                        builder))
                                                .executes(ctx -> {
                                                    int configId = IntegerArgumentType.getInteger(ctx, "config_id");
                                                    int worldIndex = IntegerArgumentType.getInteger(ctx, "world_index");
                                                    String filename = StringArgumentType.getString(ctx, "filename");
                                                    CommandSource source = ctx.getSource();
                                                    source.sendSuccess(new StringTextComponent("[MineBackup] 正在请求还原: " + filename), false);
                                                    OpenSocketQuerier.query(MineBackup.QUERIER_APP_ID, MineBackup.QUERIER_SOCKET_ID,
                                                                    String.format("RESTORE %d %d %s", configId, worldIndex, filename))
                                                            .thenAccept(response -> {
                                                                source.getServer().execute(() -> {
                                                                    if (response == null) {
                                                                        source.sendFailure(new StringTextComponent("[MineBackup] 无响应"));
                                                                        return;
                                                                    }
                                                                    if (response.startsWith("OK:")) {
                                                                        source.sendSuccess(new StringTextComponent("[MineBackup] 还原已开始。"), false);
                                                                    } else {
                                                                        source.sendFailure(new StringTextComponent("[MineBackup] 错误: " + response));
                                                                    }
                                                                });
                                                            });
                                                    return 1;
                                                }))))
                ));
    }

    private static CompletableFuture<Suggestions> suggestBackupFiles(int configId, int worldIndex, SuggestionsBuilder builder) {
        String command = String.format("LIST_BACKUPS %d %d", configId, worldIndex);
        return OpenSocketQuerier.query(MineBackup.QUERIER_APP_ID, MineBackup.QUERIER_SOCKET_ID, command)
                .thenApply(response -> {
                    if (response != null && response.startsWith("OK:")) {
                        String data = response.substring(3);
                        String[] files = data.split(";");
                        String remainingLower = builder.getRemaining().toLowerCase();
                        for (String file : files) {
                            if (!file.isEmpty() && file.toLowerCase().startsWith(remainingLower)) {
                                builder.suggest(file);
                            }
                        }
                    }
                    return builder.build();
                });
    }
}