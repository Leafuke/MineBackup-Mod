package com.leafuke.minebackup;

import com.leafuke.minebackup.knotlink.OpenSocketQuerier;
import com.leafuke.minebackup.restore.HotRestoreState;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.network.chat.Component;

/**
 * MineBackup 客户端初始化器（Fabric 1.21.11+，使用 Mojang 官方映射）
 * 负责处理客户端特有的功能，特别是热还原后的自动重连逻辑
 *
 * 参考 QuickBackupM-Reforged 的实现方式进行优化
 */
public class MineBackupClient implements ClientModInitializer {

    // 用于在客户端会话中暂存需要自动重连的世界ID（对应存档文件夹名）
    public static volatile String worldToRejoin = null;
    // 标记是否准备好重新加入世界（收到还原完成信号后设置为true）
    public static volatile boolean readyToRejoin = false;

    // 重连等待计数器和最大重试次数
    private static int rejoinTickCounter = 0;
    private static final int REJOIN_DELAY_TICKS = 40; // 等待2秒后尝试重连，确保文件操作完成
    private static int retryCount = 0;
    private static final int MAX_RETRY_COUNT = 5; // 增加重试次数

    // 标记是否已经开始断开连接流程
    private static volatile boolean disconnectInitiated = false;
    // 标记客户端是否完全断开（用于等待断开完成）
    private static int disconnectWaitTicks = 0;
    private static final int DISCONNECT_WAIT_TICKS = 20; // 等待1秒确保完全断开

    // KnotLink 查询常量
    private static final String QUERIER_APP_ID = "0x00000020";
    private static final String QUERIER_SOCKET_ID = "0x00000010";

    // 重连完成检测：等待 client.level != null 来确认世界加载成功
    private static volatile boolean waitingForRejoinCompletion = false;
    private static int rejoinCompletionTimeoutTicks = 0;
    private static final int REJOIN_COMPLETION_TIMEOUT_TICKS = 600; // 30秒超时

    @Override
    public void onInitializeClient() {
        MineBackup.LOGGER.info("[MineBackup] 客户端初始化完成 (Fabric 1.21.11+)");

        // 注册客户端tick事件，用于监听还原完成信号并自动重连
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    /**
     * 客户端tick处理
     * 负责监听还原完成信号并执行自动重连流程
     */
    private void onClientTick(Minecraft client) {
        // ========== 检测世界重连是否已成功完成 ==========
        if (waitingForRejoinCompletion) {
            if (client.level != null) {
                // 世界加载成功，通知主程序并清理状态
                waitingForRejoinCompletion = false;
                rejoinCompletionTimeoutTicks = 0;
                MineBackup.LOGGER.info("[MineBackup] 世界重连成功，发送 REJOIN_RESULT success");
                OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, "REJOIN_RESULT success");
                retryCount = 0;
                worldToRejoin = null;
                HotRestoreState.reset();
                return;
            }

            rejoinCompletionTimeoutTicks++;
            if (rejoinCompletionTimeoutTicks >= REJOIN_COMPLETION_TIMEOUT_TICKS) {
                // 重连超时，转入失败处理并允许重试
                waitingForRejoinCompletion = false;
                rejoinCompletionTimeoutTicks = 0;
                MineBackup.LOGGER.warn("[MineBackup] 世界重连超时，发送 REJOIN_RESULT failure");
                OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, "REJOIN_RESULT failure timeout");
                String lid = worldToRejoin != null ? worldToRejoin : "";
                handleRejoinFailure(client, lid, new Exception("Rejoin timed out after 30 seconds"));
                return;
            }
            return;
        }

        // ========== 收到还原完成信号后，延迟尝试重连 ==========
        if (readyToRejoin && worldToRejoin != null) {
            // 确保集成服务器已完全关闭，避免会话锁冲突
            if (client.getSingleplayerServer() != null) {
                return;
            }

            rejoinTickCounter++;

            // 延迟一段时间再执行，确保世界文件已被完全还原
            if (rejoinTickCounter >= REJOIN_DELAY_TICKS) {
                rejoinTickCounter = 0;
                readyToRejoin = false;
                disconnectInitiated = false;
                disconnectWaitTicks = 0;

                String levelId = sanitizeLevelId(worldToRejoin);
                if (levelId == null) {
                    MineBackup.LOGGER.warn("[MineBackup] 自动重连目标世界ID无效，取消重连: {}", worldToRejoin);
                    OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, "REJOIN_RESULT failure invalid_level_id");
                    resetRestoreState();
                    return;
                }
                worldToRejoin = levelId;
                // 在主线程上执行重连操作
                client.execute(() -> attemptAutoRejoin(client, levelId));
            }
        } else {
            rejoinTickCounter = 0;
        }

        // 处理断开连接后的等待
        if (disconnectInitiated && client.level == null) {
            disconnectWaitTicks++;
            if (disconnectWaitTicks >= DISCONNECT_WAIT_TICKS) {
                disconnectInitiated = false;
                disconnectWaitTicks = 0;
                // 断开完成后，设置准备重连标志
                if (worldToRejoin != null) {
                    readyToRejoin = true;
                }
            }
        }
    }

    /**
     * 尝试自动重新加入指定的单人世界
     * 参考 QuickBackupM-Reforged 的 ClientRestoreDelegate 实现
     *
     * @param client Minecraft客户端实例
     * @param levelId 世界存档文件夹名
     */
    private void attemptAutoRejoin(Minecraft client, String levelId) {
        try {
            levelId = sanitizeLevelId(levelId);
            if (levelId == null) {
                throw new IllegalArgumentException("Invalid level id for auto rejoin");
            }
            MineBackup.LOGGER.info("[MineBackup] 开始自动重连流程，目标世界: {}", levelId);

            // 显示正在重连的提示界面
            client.setScreen(new RestoreMessageScreen(
                Component.translatable("minebackup.message.restore.rejoining"),
                this::onCancelRestore
            ));

            // 确保当前没有运行中的世界
            if (client.level != null) {
                MineBackup.LOGGER.info("[MineBackup] 检测到当前有世界运行，先断开连接");
                try {
                    client.disconnect(new TitleScreen(), false);
                } catch (Exception e) {
                    MineBackup.LOGGER.warn("[MineBackup] 断开世界时出现异常: {}", e.getMessage());
                }
                // 等待下一个tick周期再尝试加入
                worldToRejoin = levelId;
                disconnectInitiated = true;
                disconnectWaitTicks = 0;
                return;
            }

            // 直接启动集成服务器
            startIntegratedServer(client, levelId);

        } catch (Exception e) {
            MineBackup.LOGGER.error("[MineBackup] 自动重连失败: {}", e.getMessage(), e);
            handleRejoinFailure(client, levelId, e);
        }
    }

    /**
     * 启动集成服务器并加入指定世界
     * 使用 Mojang 官方映射的 API（参考 QuickBackupM-Reforged）
     * 不立即清理状态，而是通过 onClientTick 检测世界加载成功后再清理
     */
    private void startIntegratedServer(Minecraft client, String levelId) {
        try {
            levelId = sanitizeLevelId(levelId);
            if (levelId == null) {
                throw new IllegalArgumentException("Invalid level id for integrated server start");
            }
            final String targetLevelId = levelId;
            // 确保集成服务器已完全关闭
            if (client.getSingleplayerServer() != null) {
                MineBackup.LOGGER.info("[MineBackup] 集成服务器仍存在，等待关闭后重试...");
                readyToRejoin = true;
                worldToRejoin = targetLevelId;
                return;
            }

            MineBackup.LOGGER.info("[MineBackup] 尝试启动集成服务器，世界: {}", targetLevelId);
            // 标记正在等待重连完成
            waitingForRejoinCompletion = true;
            rejoinCompletionTimeoutTicks = 0;

            // 使用 createWorldOpenFlows().openWorld() 方法，传入非 null 回调
            var worldOpenFlows = client.createWorldOpenFlows();
            worldOpenFlows.openWorld(targetLevelId, () -> {
                // 此回调在世界加载被取消时执行
                MineBackup.LOGGER.warn("[MineBackup] 世界加载已取消: {}", targetLevelId);
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
     * 处理重连失败的情况
     * 会尝试重试，超过最大次数后通知主程序并回退到世界选择界面
     */
    private void handleRejoinFailure(Minecraft client, String levelId, Exception error) {
        retryCount++;
        MineBackup.LOGGER.warn("[MineBackup] 自动重连尝试 {}/{} 失败 ({}): {}",
                retryCount, MAX_RETRY_COUNT, levelId, error.getMessage());

        if (retryCount < MAX_RETRY_COUNT) {
            MineBackup.LOGGER.warn("[MineBackup] 准备第 {} 次重试", retryCount + 1);
            readyToRejoin = true;
            worldToRejoin = levelId;
        } else {
            MineBackup.LOGGER.error("[MineBackup] 重连失败次数超限，放弃重连并通知主程序");
            OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID,
                    "REJOIN_RESULT failure max_retries_exceeded");
            resetRestoreState();

            try {
                client.setScreen(new SelectWorldScreen(new TitleScreen()));
            } catch (Exception ex) {
                MineBackup.LOGGER.error("[MineBackup] 无法打开世界选择界面: {}", ex.getMessage());
                client.setScreen(new TitleScreen());
            }
        }
    }

    /**
     * 取消还原操作的回调
     */
    private void onCancelRestore() {
        MineBackup.LOGGER.info("[MineBackup] 用户取消了还原操作");
        resetRestoreState();
    }

    /**
     * 重置还原状态
     * 在还原完成、取消或失败时调用
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
     * 还原过程消息显示界面
     * 用于在还原/重连过程中显示状态信息
     * 提供取消按钮以防自动重连失败
     */
    private static class RestoreMessageScreen extends Screen {
        private final Component message;
        private final Runnable onCancel;

        protected RestoreMessageScreen(Component message, Runnable onCancel) {
            super(Component.empty());
            this.message = message;
            this.onCancel = onCancel;
        }

        @Override
        protected void init() {
            // 在屏幕中央偏下位置添加一个返回按钮，以防自动重连失败
            this.addRenderableWidget(Button.builder(
                Component.translatable("gui.back"),
                button -> {
                    // 重置状态并返回主界面
                    if (onCancel != null) {
                        onCancel.run();
                    }
                    MineBackupClient.resetRestoreState();
                    if (this.minecraft != null) {
                        this.minecraft.setScreen(new TitleScreen());
                    }
                }
            ).bounds(this.width / 2 - 100, this.height / 2 + 40, 200, 20).build());
        }

        @Override
        public void render(net.minecraft.client.gui.GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
            // 绘制背景
            super.renderBackground(guiGraphics, mouseX, mouseY, partialTick);

            // 绘制居中的消息文本
            guiGraphics.drawCenteredString(
                this.font,
                this.message,
                this.width / 2,
                this.height / 2 - 20,
                0xFFFFFF
            );

            // 绘制加载提示
            String dots = ".".repeat((int) (System.currentTimeMillis() / 500 % 4));
            guiGraphics.drawCenteredString(
                this.font,
                dots,
                this.width / 2,
                this.height / 2,
                0xAAAAAA
            );

            super.render(guiGraphics, mouseX, mouseY, partialTick);
        }

        @Override
        public boolean shouldCloseOnEsc() {
            return true;
        }

        @Override
        public void onClose() {
            if (onCancel != null) {
                onCancel.run();
            }
            MineBackupClient.resetRestoreState();
            super.onClose();
        }
    }
}

