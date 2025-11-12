// java
package com.leafuke.minebackup;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.world.WorldListWidget;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.text.Text;

public class MineBackupClient implements ClientModInitializer {
    // 用于在客户端会话中暂存需要自动重连的世界ID（对应存档文件夹名）
    public static String worldToRejoin = null;
    public static boolean readyToRejoin = false;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {

            if (readyToRejoin) {
                MinecraftClient mc = MinecraftClient.getInstance();
                mc.setScreen(new TitleScreen());
                readyToRejoin = false;
                String levelId = worldToRejoin;
                worldToRejoin = null;

                if (levelId != null) {
                    MineBackup.LOGGER.info("Attempting to automatically rejoin world: {}", levelId);
                    mc.execute(() -> attemptAutoRejoin(mc, levelId));
                }
            }
        });
    }

    /**
     * 使用Yarn映射下的API自动进入指定单人世界（levelId为世界存档文件夹名）。
     */
    private void attemptAutoRejoin(MinecraftClient client, String levelId) {
        try {
            // 给玩家一个提示
            client.inGameHud.getChatHud().addMessage(Text.translatable("minebackup.message.restore.rejoining"));
            // 关闭当前世界（如果有）
            if (client.world != null) {
                client.disconnect();
            }
            // 直接打开世界选择界面并自动选择目标世界
            SelectWorldScreen selectWorldScreen = new SelectWorldScreen(new TitleScreen());
            client.setScreen(selectWorldScreen);
            // 自动选择并进入目标世界（Yarn下WorldListWidget有getSaveName）
            client.execute(() -> {
                client.createIntegratedServerLoader().start(levelId, null);
//                if (selectWorldScreen.worldListWidget != null) {
//                    for (WorldListWidget.Entry entry : selectWorldScreen.worldListWidget.children()) {
//                        if (entry.getSaveName().equals(levelId)) {
//                            selectWorldScreen.worldListWidget.setSelected(entry);
//                            selectWorldScreen.joinSelectedWorld();
//                            MineBackup.LOGGER.info("Auto-joined world: {}", levelId);
//                            return;
//                        }
//                    }
//                    MineBackup.LOGGER.warn("World '{}' not found in world list.", levelId);
//                } else {
//                    MineBackup.LOGGER.warn("World list widget is null, cannot auto-join world.");
//                }
            });
        } catch (Exception e) {
            MineBackup.LOGGER.error("Auto rejoin failed for world '{}': {}", levelId, e);
            try {
                client.setScreen(new SelectWorldScreen(new TitleScreen()));
            } catch (Exception ex) {
                MineBackup.LOGGER.warn("Failed to open SelectWorldScreen after auto-rejoin failure: {}", ex.getMessage());
            }
        }
    }
}
