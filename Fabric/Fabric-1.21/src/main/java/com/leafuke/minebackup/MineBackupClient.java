package com.leafuke.minebackup;

import com.leafuke.minebackup.restore.HotRestoreState;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public class MineBackupClient implements ClientModInitializer {
    // 用于在客户端会话中暂存需要自动重连的世界ID（对应存档文件夹名）
    public static volatile String worldToRejoin = null;
    public static volatile boolean readyToRejoin = false;

    // 重连等待与重试参数
    private static int rejoinTickCounter = 0;
    private static final int REJOIN_DELAY_TICKS = 40; // 约2秒延迟，确保文件写入完成
    private static int retryCount = 0;
    private static final int MAX_RETRY_COUNT = 5;

    // 断开状态追踪
    private static volatile boolean disconnectInitiated = false;
    private static int disconnectWaitTicks = 0;
    private static final int DISCONNECT_WAIT_TICKS = 20;

    @Override
    public void onInitializeClient() {
        MineBackup.LOGGER.info("MineBackup client initialized for Fabric 1.21 (Yarn)");
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    private void onClientTick(MinecraftClient client) {
        // 收到还原完成信号后，延迟一段时间再尝试重连，避免文件占用
        if (readyToRejoin && worldToRejoin != null) {
            rejoinTickCounter++;
            if (rejoinTickCounter >= REJOIN_DELAY_TICKS) {
                rejoinTickCounter = 0;
                readyToRejoin = false;
                disconnectInitiated = false;
                disconnectWaitTicks = 0;

                String levelId = worldToRejoin;
                client.execute(() -> attemptAutoRejoin(client, levelId));
            }
        } else {
            rejoinTickCounter = 0;
        }

        // 等待断开完成后再进行重连流程
        if (disconnectInitiated && client.world == null) {
            disconnectWaitTicks++;
            if (disconnectWaitTicks >= DISCONNECT_WAIT_TICKS) {
                disconnectInitiated = false;
                disconnectWaitTicks = 0;
                if (worldToRejoin != null) {
                    readyToRejoin = true;
                }
            }
        }
    }

    /**
     * 使用 Yarn API 自动进入指定单人世界。
     */
    private void attemptAutoRejoin(MinecraftClient client, String levelId) {
        try {
            MineBackup.LOGGER.info("Attempting to automatically rejoin world: {}", levelId);

            client.setScreen(new RestoreMessageScreen(
                    Text.translatable("minebackup.message.restore.rejoining"),
                    MineBackupClient::resetRestoreState
            ));

            // 如果当前仍在世界里，先断开再等待下个 tick 重试
            if (client.world != null) {
                disconnectInitiated = true;
                disconnectWaitTicks = 0;
                try {
                    client.disconnect();
                } catch (Exception e) {
                    MineBackup.LOGGER.warn("Failed to disconnect before hot restore: {}", e.getMessage());
                }
                return;
            }

            startIntegratedServer(client, levelId);
        } catch (Exception e) {
            MineBackup.LOGGER.error("Auto rejoin failed for world '{}': {}", levelId, e.getMessage(), e);
            handleRejoinFailure(client, levelId, e);
        }
    }

    /**
     * 启动集成服务器并加入指定世界。
     */
    private void startIntegratedServer(MinecraftClient client, String levelId) {
        try {
            client.createIntegratedServerLoader().start(levelId, null);
            retryCount = 0;
            worldToRejoin = null;
            HotRestoreState.reset();
            MineBackup.LOGGER.info("Integrated server started for {}", levelId);
        } catch (Exception e) {
            handleRejoinFailure(client, levelId, e);
        }
    }

    /**
     * 处理自动重连失败的情况，支持多次重试。
     */
    private void handleRejoinFailure(MinecraftClient client, String levelId, Exception error) {
        retryCount++;
        MineBackup.LOGGER.warn("Auto rejoin attempt {} failed for {}: {}", retryCount, levelId, error.getMessage());

        if (retryCount < MAX_RETRY_COUNT) {
            readyToRejoin = true;
            worldToRejoin = levelId;
        } else {
            resetRestoreState();
            try {
                client.inGameHud.getChatHud().addMessage(Text.translatable("minebackup.message.restore.failed"));
            } catch (Exception ignored) { }
            try {
                client.setScreen(new SelectWorldScreen(new TitleScreen()));
            } catch (Exception ex) {
                MineBackup.LOGGER.warn("Failed to open SelectWorldScreen after auto-rejoin failure: {}", ex.getMessage());
                client.setScreen(new TitleScreen());
            }
        }
    }

    /**
     * 重置还原状态。
     */
    public static void resetRestoreState() {
        worldToRejoin = null;
        readyToRejoin = false;
        rejoinTickCounter = 0;
        retryCount = 0;
        disconnectInitiated = false;
        disconnectWaitTicks = 0;
        HotRestoreState.reset();
    }

    /**
     * 简单的还原状态提示界面。
     */
    private static class RestoreMessageScreen extends Screen {
        private final Text message;
        private final Runnable onCancel;

        protected RestoreMessageScreen(Text message, Runnable onCancel) {
            super(Text.empty());
            this.message = message;
            this.onCancel = onCancel;
        }

        @Override
        protected void init() {
            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.back"),
                    button -> {
                        if (onCancel != null) {
                            onCancel.run();
                        }
                        if (this.client != null) {
                            this.client.setScreen(new TitleScreen());
                        }
                    }
            ).dimensions(this.width / 2 - 100, this.height / 2 + 40, 200, 20).build());
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            this.renderBackground(context, mouseX, mouseY, delta);
            context.drawCenteredTextWithShadow(this.textRenderer, this.message, this.width / 2, this.height / 2 - 20, 0xFFFFFF);
            String dots = ".".repeat((int) (System.currentTimeMillis() / 500 % 4));
            context.drawCenteredTextWithShadow(this.textRenderer, dots, this.width / 2, this.height / 2, 0xAAAAAA);
            super.render(context, mouseX, mouseY, delta);
        }

        @Override
        public boolean shouldCloseOnEsc() {
            return true;
        }

        @Override
        public void close() {
            if (onCancel != null) {
                onCancel.run();
            }
            super.close();
        }
    }
}
