package com.leafuke.minebackup;

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
 * 负责处理客户端��有的功能，特别是热还原后的自动重连逻辑
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
        // 当收到还原完成信号时，执行自动重连流程
        if (readyToRejoin && worldToRejoin != null) {
            rejoinTickCounter++;

            // 延迟一段时间再执行，确保世界文件已被完全还原
            if (rejoinTickCounter >= REJOIN_DELAY_TICKS) {
                rejoinTickCounter = 0;
                readyToRejoin = false;
                disconnectInitiated = false;
                disconnectWaitTicks = 0;

                String levelId = worldToRejoin;
                MineBackup.LOGGER.info("[MineBackup] 准备自动重新加入世界: {}", levelId);

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
            MineBackup.LOGGER.info("[MineBackup] 开始自动重连流程，目标世界: {}", levelId);

            // 显示正在重连的提示界面
            client.setScreen(new RestoreMessageScreen(
                Component.translatable("minebackup.message.restore.rejoining"),
                this::onCancelRestore
            ));

            // 确保当前没有运行中的世界
            if (client.level != null) {
                MineBackup.LOGGER.info("[MineBackup] 检测到当前有世界运行，先断开连接");
                // 使用与 QuickBackupM 类似的断开方式
//                try {
//                    client.level.disconnect();
//                    client.disconnect();
//                } catch (Exception e) {
//                    MineBackup.LOGGER.warn("[MineBackup] 断开世界时出现异常: {}", e.getMessage());
//                }
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
     *
     * @param client Minecraft客户端实例
     * @param levelId 世界存档文件夹名
     */
    private void startIntegratedServer(Minecraft client, String levelId) {
        try {
            MineBackup.LOGGER.info("[MineBackup] 尝试启动集成服务器，世界: {}", levelId);

            // MC 1.21.11+ 使用 Mojang 官方映射
            // 使用 createWorldOpenFlows().openWorld() 方法（与 QuickBackupM-Reforged 一致）
            var worldOpenFlows = client.createWorldOpenFlows();
            worldOpenFlows.openWorld(levelId, () -> {
                // 加载完成后的回调 - 清理状态
                retryCount = 0;
                worldToRejoin = null;
                HotRestoreState.reset();
                MineBackup.LOGGER.info("[MineBackup] 世界 {} 加载成功！", levelId);
            });

        } catch (Exception e) {
            MineBackup.LOGGER.error("[MineBackup] 启动集成服务器失败: {}", e.getMessage(), e);
            handleRejoinFailure(client, levelId, e);
        }
    }

    /**
     * 处理重连失败的情况
     * 会尝试重试，超过最大次数后回退到世界选择界面
     *
     * @param client Minecraft客户端实例
     * @param levelId 世界存档文件夹名
     * @param error 失败原因
     */
    private void handleRejoinFailure(Minecraft client, String levelId, Exception error) {
        retryCount++;

        if (retryCount < MAX_RETRY_COUNT) {
            MineBackup.LOGGER.warn("[MineBackup] 重连失败，准备第 {} 次重试", retryCount + 1);
            // 设置标记，下一个tick周期会重新尝试
            readyToRejoin = true;
            worldToRejoin = levelId;
        } else {
            MineBackup.LOGGER.error("[MineBackup] 重连失败次数过多，回退到世界选择界面");
            resetRestoreState();

            // 显示错误提示并跳转到世界选择界面
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
        HotRestoreState.reset();
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

