package com.leafuke.minebackup;

import com.leafuke.minebackup.knotlink.OpenSocketQuerier;
import com.leafuke.minebackup.restore.HotRestoreState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent; // TickEvent 的包和类名都有变化
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.lang.reflect.Method;
import java.util.Optional;

public class MineBackupClient {

    public static volatile String worldToRejoin = null;
    public static volatile boolean readyToRejoin = false;

    private static int rejoinTickCounter = 0;
    private static final int REJOIN_DELAY_TICKS = 40;
    private static int retryCount = 0;
    private static final int MAX_RETRY_COUNT = 5;

    private static volatile boolean disconnectInitiated = false;
    private static int disconnectWaitTicks = 0;
    private static final int DISCONNECT_WAIT_TICKS = 20;

    private static final String QUERIER_APP_ID = "0x00000020";
    private static final String QUERIER_SOCKET_ID = "0x00000010";

    private static volatile boolean waitingForRejoinCompletion = false;
    private static int rejoinCompletionTimeoutTicks = 0;
    private static final int REJOIN_COMPLETION_TIMEOUT_TICKS = 600;

    public static void initialize() {
        MinecraftForge.EVENT_BUS.register(new MineBackupClient());
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        Minecraft client = Minecraft.getInstance();

        if (waitingForRejoinCompletion) {
            if (client.level != null) {
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
                waitingForRejoinCompletion = false;
                rejoinCompletionTimeoutTicks = 0;
                MineBackup.LOGGER.warn("世界重连超时，发送 REJOIN_RESULT failure");
                OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, "REJOIN_RESULT failure timeout");
                String lid = worldToRejoin != null ? worldToRejoin : "";
                handleRejoinFailure(client, lid, new Exception("Rejoin timed out after 30 seconds"));
                return;
            }
            return;
        }

        if (readyToRejoin && worldToRejoin != null) {
            if (hasIntegratedServerRunning(client)) {
                return;
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

        if (disconnectInitiated && client.level == null) {
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

    private void attemptAutoRejoin(Minecraft client, String levelId) {
        try {
            levelId = sanitizeLevelId(levelId);
            if (levelId == null) {
                throw new IllegalArgumentException("Invalid level id for auto rejoin");
            }
            MineBackup.LOGGER.info("Attempting to automatically rejoin world: {}", levelId);

            client.setScreen(new SimpleMessageScreen(Component.translatable("minebackup.message.restore.rejoining")));

            if (client.level != null) {
                disconnectInitiated = true;
                disconnectWaitTicks = 0;
                try {
                    safeInvokeDisconnect(client, Component.translatable("minebackup.message.restore.rejoining"));
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

    private void startIntegratedServer(Minecraft client, String levelId) {
        try {
            levelId = sanitizeLevelId(levelId);
            if (levelId == null) {
                throw new IllegalArgumentException("Invalid level id for integrated server start");
            }
            final String targetLevelId = levelId;

            if (hasIntegratedServerRunning(client)) {
                MineBackup.LOGGER.info("集成服务器仍存在，等待关闭后重试...");
                readyToRejoin = true;
                worldToRejoin = targetLevelId;
                return;
            }

            MineBackup.LOGGER.info("正在启动集成服务器加载世界: {}", targetLevelId);
            waitingForRejoinCompletion = true;
            rejoinCompletionTimeoutTicks = 0;

            try {
                deleteSessionLockForLevel(client, targetLevelId);
            } catch (Exception e) {
                MineBackup.LOGGER.warn("Could not delete session lock for '{}': {}", targetLevelId, e.getMessage());
            }

            boolean started = tryStartIntegratedServer(client, targetLevelId);
            if (!started) {
                waitingForRejoinCompletion = false;
                throw new IllegalStateException("Unable to start integrated server for auto rejoin");
            }
        } catch (Exception e) {
            waitingForRejoinCompletion = false;
            handleRejoinFailure(client, levelId, e);
        }
    }

    private void handleRejoinFailure(Minecraft client, String levelId, Exception error) {
        retryCount++;
        MineBackup.LOGGER.warn("自动重连尝试 {}/{} 失败 ({}): {}", retryCount, MAX_RETRY_COUNT, levelId, error.getMessage());

        if (retryCount < MAX_RETRY_COUNT) {
            readyToRejoin = true;
            worldToRejoin = levelId;
        } else {
            MineBackup.LOGGER.error("重连失败次数超限，放弃重连并通知主程序");
            OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID,
                    "REJOIN_RESULT failure max_retries_exceeded");
            resetRestoreState();
            try {
                if (client.player != null) {
                    client.player.sendSystemMessage(Component.translatable("minebackup.message.restore.failed"));
                }
            } catch (Exception ignored) { }
            try {
                client.setScreen(new SelectWorldScreen(new TitleScreen()));
            } catch (Exception ex) {
                MineBackup.LOGGER.warn("无法打开世界选择界面: {}", ex.getMessage());
                client.setScreen(new TitleScreen());
            }
        }
    }

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

    public static void showRestoreSuccessOverlay() {
        try {
            Minecraft client = Minecraft.getInstance();
            if (client == null) {
                return;
            }
            client.execute(() -> {
                if (client.player != null) {
                    client.player.displayClientMessage(Component.translatable("minebackup.message.restore.success_overlay"), true);
                }
            });
        } catch (Exception ignored) {
        }
    }

    private static String sanitizeLevelId(String rawLevelId) {
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

    private static boolean hasIntegratedServerRunning(Minecraft client) {
        Object server = invokeFirstNoArgMethod(client, "getSingleplayerServer");
        if (server != null) {
            return true;
        }
        return invokeBooleanPossibleMethods(client, "isLocalServer", "isIntegratedServerRunning", "isLocalServerRunning", "isSingleplayer");
    }


    private static boolean invokeBooleanPossibleMethods(Object target, String... methodNames) {
        for (String name : methodNames) {
            try {
                Method m = target.getClass().getMethod(name);
                Object res = m.invoke(target);
                if (res instanceof Boolean) return (Boolean) res;
            } catch (NoSuchMethodException ignored) {
            } catch (Exception e) {
                MineBackup.LOGGER.debug("invokeBooleanPossibleMethods '{}' failed: {}", name, e.getMessage());
            }
        }
        return false;
    }

    private static void safeInvokeDisconnect(Minecraft client, Component notice) {
        try {
            if (client.level != null) {
                client.level.disconnect();
            }
        } catch (Exception e) {
            MineBackup.LOGGER.debug("level.disconnect() failed: {}", e.getMessage());
        }

        try {
            Method m = client.getClass().getMethod("disconnect", Screen.class);
            m.invoke(client, new SimpleMessageScreen(notice));
            return;
        } catch (NoSuchMethodException ignored) {
        } catch (Exception e) {
            MineBackup.LOGGER.debug("disconnect(Screen) failed: {}", e.getMessage());
        }

        try {
            Method m = client.getClass().getMethod("disconnect");
            m.invoke(client);
            return;
        } catch (NoSuchMethodException ignored) {
        } catch (Exception e) {
            MineBackup.LOGGER.debug("disconnect() failed: {}", e.getMessage());
        }

        try {
            client.setScreen(new SimpleMessageScreen(notice));
        } catch (Exception e) {
            MineBackup.LOGGER.debug("Fallback setScreen message failed: {}", e.getMessage());
        }
    }

    private static void deleteSessionLockForLevel(Minecraft client, String levelId) {
        Object levelSource = invokeFirstNoArgMethod(client, "getLevelSource", "getLevelStorage", "getSaveLoader");
        if (levelSource == null) {
            MineBackup.LOGGER.debug("No level storage/source object found via reflection.");
            return;
        }
        Optional<Method> createAccess = findMethodByNameAndParamCount(levelSource.getClass(), new String[]{"createAccess"}, 1);
        if (createAccess.isPresent()) {
            try {
                Object access = createAccess.get().invoke(levelSource, levelId);
                if (access != null) {
                    tryInvokeNoArgOnObject(access, "deleteLock", "close", "deleteSessionLock");
                }
            } catch (Exception e) {
                MineBackup.LOGGER.debug("createAccess invocation failed: {}", e.getMessage());
            }
        }
    }

    private static Object invokeFirstNoArgMethod(Object target, String... methodNames) {
        for (String name : methodNames) {
            try {
                Method m = target.getClass().getMethod(name);
                return m.invoke(target);
            } catch (NoSuchMethodException ignored) {
            } catch (Exception e) {
                MineBackup.LOGGER.debug("invokeFirstNoArgMethod '{}' failed: {}", name, e.getMessage());
            }
        }
        return null;
    }

    private static Optional<Method> findMethodByNameAndParamCount(Class<?> cls, String[] names, int paramCount) {
        for (String name : names) {
            for (Method m : cls.getMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == paramCount) return Optional.of(m);
            }
        }
        return Optional.empty();
    }

    private static void tryInvokeNoArgOnObject(Object target, String... methodNames) {
        for (String name : methodNames) {
            try {
                Method m = target.getClass().getMethod(name);
                m.invoke(target);
                MineBackup.LOGGER.debug("Successfully invoked '{}' on {}", name, target.getClass().getName());
                return;
            } catch (NoSuchMethodException ignored) {
            } catch (Exception e) {
                MineBackup.LOGGER.debug("tryInvokeNoArgOnObject '{}' failed: {}", name, e.getMessage());
            }
        }
    }

    private static boolean tryStartIntegratedServer(Minecraft client, String levelId) {
        try {
            Object worldOpenFlows = invokeFirstNoArgMethod(client, "createWorldOpenFlows");
            if (worldOpenFlows != null) {
                try {
                    Method openWorld = worldOpenFlows.getClass().getMethod("openWorld", String.class, Runnable.class);
                    openWorld.invoke(worldOpenFlows, levelId, (Runnable) () -> client.setScreen(null));
                    MineBackup.LOGGER.info("Invoked createWorldOpenFlows().openWorld for levelId={}", levelId);
                    return true;
                } catch (NoSuchMethodException ignored) {
                }

                Method loadLevel = worldOpenFlows.getClass().getMethod("loadLevel", Screen.class, String.class);
                loadLevel.invoke(worldOpenFlows, new TitleScreen(), levelId);
                MineBackup.LOGGER.info("Invoked createWorldOpenFlows().loadLevel for levelId={}", levelId);
                return true;
            }
        } catch (Exception e) {
            MineBackup.LOGGER.debug("createWorldOpenFlows().loadLevel() reflection failed: {}", e.getMessage());
        }
        String[] fallbacks = new String[]{"startIntegratedServer", "loadLevel"};
        for (String name : fallbacks) {
            try {
                for (Method m : client.getClass().getMethods()) {
                    if (m.getName().equals(name) && m.getParameterCount() == 1 && m.getParameterTypes()[0] == String.class) {
                        m.invoke(client, levelId);
                        return true;
                    }
                }
            } catch (Exception e) {
                MineBackup.LOGGER.debug("{} invocation failed: {}", name, e.getMessage());
            }
        }
        return false;
    }

    private static class SimpleMessageScreen extends Screen {
        private final Component message;

        protected SimpleMessageScreen(Component message) {
            super(Component.empty());
            this.message = message;
        }

        @Override
        protected void init() {
            addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> {
                Minecraft.getInstance().setScreen(null);
            }).bounds(this.width / 2 - 100, this.height / 2 + 20, 200, 20).build());
        }

        @Override
        public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
            super.render(guiGraphics, mouseX, mouseY, partialTick);
            guiGraphics.drawCenteredString(this.font, this.message, this.width / 2, this.height / 2 - 10, 0xFFFFFF);
        }

        @Override
        public boolean shouldCloseOnEsc() {
            return true;
        }
    }
}