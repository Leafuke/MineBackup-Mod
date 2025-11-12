package com.leafuke.minebackup;

import com.leafuke.minebackup.knotlink.SignalSubscriber;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
// 修正：导入正确的 FML 服务器事件
import net.minecraftforge.fml.event.server.FMLServerStartingEvent;
import net.minecraftforge.fml.event.server.FMLServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

@Mod(MineBackup.MOD_ID)
public class MineBackup {

    public static final String MOD_ID = "minebackup";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static SignalSubscriber knotLinkSubscriber;
    private static volatile MinecraftServer serverInstance;

    public static final String BROADCAST_APP_ID = "0x00000020";
    public static final String BROADCAST_SIGNAL_ID = "0x00000020";

    public MineBackup() {
        MinecraftForge.EVENT_BUS.register(this);
        LOGGER.info("Initializing MineBackup for Forge 1.16.5...");
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        Command.register(event.getDispatcher());
    }

    // 修正：监听 FMLServerStartingEvent
    @SubscribeEvent
    public void onServerStarting(FMLServerStartingEvent event) {
        LOGGER.info("MineBackup Mod: Server is starting, initializing KnotLink Subscriber...");
        serverInstance = event.getServer();
        knotLinkSubscriber = new SignalSubscriber(BROADCAST_APP_ID, BROADCAST_SIGNAL_ID);
        knotLinkSubscriber.setSignalListener(this::handleBroadcastEvent);
        new Thread(knotLinkSubscriber::start).start();
    }

    // 修正：监听 FMLServerStoppingEvent
    @SubscribeEvent
    public void onServerStopping(FMLServerStoppingEvent event) {
        LOGGER.info("MineBackup Mod: Server is stopping, shutting down KnotLink Subscriber...");
        if (knotLinkSubscriber != null) {
            knotLinkSubscriber.stop();
        }
        serverInstance = null;
    }

    // 核心逻辑方法无需修改，因为我上次已经将它们改为了 1.16.5 的格式
    private Map<String, String> parsePayload(String payload) {
        Map<String, String> dataMap = new HashMap<>();
        if (payload == null || payload.isEmpty()) return dataMap;
        for (String pair : payload.split(";")) {
            String[] keyValue = pair.split("=", 2);
            if (keyValue.length == 2) {
                dataMap.put(keyValue[0].trim(), keyValue[1].trim());
            }
        }
        return dataMap;
    }

    private void handleBroadcastEvent(String payload) {
        if (serverInstance == null) return;

//        if ("minebackup save".equals(payload)) {
//            serverInstance.execute(() -> {
//                LOGGER.info("Received 'minebackup save' command, executing immediate world save.");
//                serverInstance.getPlayerList().broadcastSystemMessage(new StringTextComponent("§6[MineBackup] §e正在执行远程保存指令..."), Util.NIL_UUID);
//                boolean allLevelsSaved = serverInstance.save(true, true, true);
//                if (allLevelsSaved) {
//                    serverInstance.getPlayerList().broadcastSystemMessage(new StringTextComponent("§a[MineBackup] §e远程保存完成！"), Util.NIL_UUID);
//                } else {
//                    serverInstance.getPlayerList().broadcastSystemMessage(new StringTextComponent("§c[MineBackup] §e远程保存失败！"), Util.NIL_UUID);
//                }
//            });
//            return;
//        }

        LOGGER.info("Received MineBackup broadcast event: {}", payload);
        Map<String, String> eventData = parsePayload(payload);
        String eventType = eventData.get("event");
        if (eventType == null) return;

        ITextComponent message = null;
        switch (eventType) {
            case "backup_started":
                message = new StringTextComponent(String.format("§6[MineBackup] §e世界 '%s' 的备份任务已开始...", eventData.getOrDefault("world", "未知世界")));
                break;
            case "restore_started":
                message = new StringTextComponent(String.format("§6[MineBackup] §e世界 '%s' 的还原任务已开始...", eventData.getOrDefault("world", "未知世界")));
                break;
            // ... apply StringTextComponent to all other cases
            case "backup_success":
                message = new StringTextComponent(String.format("§a[MineBackup] §2备份成功! §e世界 '%s' §a已保存为 §f%s", eventData.getOrDefault("world", "未知世界"), eventData.getOrDefault("file", "未知文件")));
                break;
            case "backup_failed":
                message = new StringTextComponent(String.format("§c[MineBackup] §4备份失败! §e世界 '%s'. §c原因: %s", eventData.getOrDefault("world", "未知世界"), eventData.getOrDefault("error", "未知错误")));
                break;
            case "game_session_end":
                message = new StringTextComponent(String.format("§7[MineBackup] 游戏会话结束: %s. 后台可能已触发退出时备份。", eventData.getOrDefault("world", "未知世界")));
                break;
            case "auto_backup_started":
                message = new StringTextComponent(String.format("§6[MineBackup] §e世界 '%s' 的自动备份任务已开始...", eventData.getOrDefault("world", "未知世界")));
                break;
        }

//        if ("pre_hot_backup".equals(eventType)) {
//            serverInstance.execute(() -> {
//                LOGGER.info("Executing immediate save for pre_hot_backup event.");
//                String worldName = eventData.getOrDefault("world", "未知世界");
//                serverInstance.getPlayerList().broadcastSystemMessage(new StringTextComponent(String.format("§6[MineBackup] §e收到热备份请求，正在为世界 '%s' 保存最新数据...", worldName)), Util.NIL_UUID);
//                boolean allSaved = serverInstance.save(true, true, true);
//                if (!allSaved) {
//                    LOGGER.warn("One or more levels failed to save during pre_hot_backup for world: {}", worldName);
//                    serverInstance.getPlayerList().broadcastSystemMessage(new StringTextComponent(String.format("§c[MineBackup] §4警告: 世界 '%s' 的部分数据保存失败，热备份可能不完整！", worldName)), Util.NIL_UUID);
//                }
//                LOGGER.info("World saved successfully for hot backup.");
//                serverInstance.getPlayerList().broadcastSystemMessage(new StringTextComponent("§a[MineBackup] §e世界保存完毕，备份程序已开始工作。"), Util.NIL_UUID);
//            });
//        } else if ("game_session_start".equals(eventType)) {
//            LOGGER.info("MineBackup detected game session start for world: {}", eventData.getOrDefault("world", "未知"));
//        }

//        if (message != null) {
//            final ITextComponent finalMessage = message;
//            serverInstance.execute(() -> serverInstance.getPlayerList().broadcastSystemMessage(finalMessage, Util.NIL_UUID));
//        }
    }
}