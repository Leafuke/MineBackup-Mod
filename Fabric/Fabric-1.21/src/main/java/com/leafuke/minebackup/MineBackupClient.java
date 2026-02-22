package com.leafuke.minebackup;

import com.leafuke.minebackup.knotlink.OpenSocketQuerier;
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

    // KnotLink 查询常量
    private static final String QUERIER_APP_ID = "0x00000020";
    private static final String QUERIER_SOCKET_ID = "0x00000010";

    // 重连完成检测：等待 client.world != null 来确认世界加载成功
    private static volatile boolean waitingForRejoinCompletion = false;
    private static int rejoinCompletionTimeoutTicks = 0;
    private static final int REJOIN_COMPLETION_TIMEOUT_TICKS = 600; // 30秒超时

    @Override
    public void onInitializeClient() {
        MineBackup.LOGGER.info("MineBackup client initialized for Fabric 1.21 (Yarn)");
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    private void onClientTick(MinecraftClient client) {
        // ========== 检测世界重连是否已成功完成 ==========
        if (waitingForRejoinCompletion) {
            if (client.world != null) {
                // 已成功进入世界，通知主程序重连成功
                waitingForRejoinCompletion = false;
                rejoinCompletionTimeoutTicks = 0;
                MineBackup.LOGGER.info("世界重连成功，发送 REJOIN_RESULT success");
                OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, "REJOIN_RESULT success");
                retryCount = 0;
                worldToRejoin = null;
                HotRestoreState.reset();
                return;
            }
            rejoinCompletionTimeoutTicks++;
            if (rejoinCompletionTimeoutTicks >= REJOIN_COMPLETION_TIMEOUT_TICKS) {
                // 重连超时
                waitingForRejoinCompletion = false;
                rejoinCompletionTimeoutTicks = 0;
                MineBackup.LOGGER.warn("世界重连超时，发送 REJOIN_RESULT failure");
                OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, "REJOIN_RESULT failure timeout");
                String lid = worldToRejoin != null ? worldToRejoin : "";
                handleRejoinFailure(client, lid, new Exception("Rejoin timed out after 30 seconds"));
                return;
            }
            return; // 等待重连完成期间不执行其他逻辑
        }

        // ========== 收到还原完成信号后，延迟尝试重连 ==========
        if (readyToRejoin && worldToRejoin != null) {
            // 确保集成服务器已完全关闭，避免会话锁冲突
            if (client.getServer() != null) {
                return; // 等待服务器完全关闭
            }
            rejoinTickCounter++;
            if (rejoinTickCounter >= REJOIN_DELAY_TICKS) {
                rejoinTickCounter = 0;
                readyToRejoin = false;
                disconnectInitiated = false;
                disconnectWaitTicks = 0;

                String levelId = sanitizeLevelId(worldToRejoin);
                if (levelId == null) {
                    MineBackup.LOGGER.warn("自动重连目标世界ID无效，取消重连: {}", worldToRejoin);
                    OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, "REJOIN_RESULT failure invalid_level_id");
                    resetRestoreState();
                    return;
                }
                worldToRejoin = levelId;
                client.execute(() -> attemptAutoRejoin(client, levelId));
            }
        } else {
            rejoinTickCounter = 0;
        }

        // ========== 等待断开完成后再进行重连流程 ==========
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
            levelId = sanitizeLevelId(levelId);
            if (levelId == null) {
                throw new IllegalArgumentException("Invalid level id for auto rejoin");
            }
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
     * 使用非 null 回调避免加载失败时可能的空指针问题。
     * 不立即清理状态，而是通过 onClientTick 检测世界加载成功后再清理。
     */
    private void startIntegratedServer(MinecraftClient client, String levelId) {
        try {
            levelId = sanitizeLevelId(levelId);
            if (levelId == null) {
                throw new IllegalArgumentException("Invalid level id for integrated server start");
            }
            final String targetLevelId = levelId;
            // 确保集成服务器已完全关闭
            if (client.getServer() != null) {
                MineBackup.LOGGER.info("集成服务器仍存在，等待关闭后重试...");
                readyToRejoin = true;
                worldToRejoin = targetLevelId;
                return;
            }

            MineBackup.LOGGER.info("正在启动集成服务器加载世界: {}", targetLevelId);
            // 标记正在等待重连完成
            waitingForRejoinCompletion = true;
            rejoinCompletionTimeoutTicks = 0;

            // 使用正确的回调（非 null），避免加载被取消时出现空指针
            client.createIntegratedServerLoader().start(targetLevelId, () -> {
                // 此回调在世界加载被取消时执行
                MineBackup.LOGGER.warn("世界加载已取消: {}", targetLevelId);
                waitingForRejoinCompletion = false;
                OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, "REJOIN_RESULT failure cancelled");
                client.setScreen(new TitleScreen());
            });
        } catch (Exception e) {
            waitingForRejoinCompletion = false;
            handleRejoinFailure(client, levelId, e);
        }
    }

    /**
     * 处理自动重连失败的情况，支持多次重试。
     * 超过最大重试次数后通知主程序并回退到世界选择界面。
     */
    private void handleRejoinFailure(MinecraftClient client, String levelId, Exception error) {
        retryCount++;
        MineBackup.LOGGER.warn("自动重连尝试 {}/{} 失败 ({}): {}", retryCount, MAX_RETRY_COUNT, levelId, error.getMessage());

        if (retryCount < MAX_RETRY_COUNT) {
            readyToRejoin = true;
            worldToRejoin = levelId;
        } else {
            // 所有重试均失败，通知主程序
            MineBackup.LOGGER.error("重连失败次数超限，放弃重连并通知主程序");
            OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID,
                    "REJOIN_RESULT failure max_retries_exceeded");
            resetRestoreState();
            try {
                client.inGameHud.getChatHud().addMessage(Text.translatable("minebackup.message.restore.failed"));
            } catch (Exception ignored) { }
            try {
                client.setScreen(new SelectWorldScreen(new TitleScreen()));
            } catch (Exception ex) {
                MineBackup.LOGGER.warn("无法打开世界选择界面: {}", ex.getMessage());
                client.setScreen(new TitleScreen());
            }
        }
    }

    /**
     * 重置所有还原相关状态。
     */
    public static void resetRestoreState() {
        worldToRejoin = null;
        readyToRejoin = false;
        rejoinTickCounter = 0;
        retryCount = 0;
        disconnectInitiated = false;
        disconnectWaitTicks = 0;
        waitingForRejoinCompletion = false;
        rejoinCompletionTimeoutTicks = 0;
        HotRestoreState.reset();
    }

    /**
     * 规范化并校验世界目录名，避免出现 "." 等非法值导致读取 saves/. 失败。
     */
    private String sanitizeLevelId(String rawLevelId) {
        if (rawLevelId == null) {
            return null;
        }
        String normalized = rawLevelId.trim();
        if (normalized.isEmpty() || ".".equals(normalized) || "..".equals(normalized)) {
            return null;
        }
        if (normalized.contains("/") || normalized.contains("\\")) {
            return null;
        }
        return normalized;
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
