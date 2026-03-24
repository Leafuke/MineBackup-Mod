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

public final class ClientRejoinController {
    private static final String QUERIER_APP_ID = "0x00000020";
    private static final String QUERIER_SOCKET_ID = "0x00000010";
    private static final int REJOIN_DELAY_TICKS = 40;
    private static final int MAX_RETRY_COUNT = 5;
    private static final int DISCONNECT_WAIT_TICKS = 20;
    private static final int REJOIN_COMPLETION_TIMEOUT_TICKS = 600;

    private static volatile String worldToRejoin;
    private static volatile boolean readyToRejoin;
    private static volatile boolean disconnectInitiated;
    private static volatile boolean waitingForRejoinCompletion;

    private static int rejoinTickCounter;
    private static int retryCount;
    private static int disconnectWaitTicks;
    private static int rejoinCompletionTimeoutTicks;

    private ClientRejoinController() {
    }

    public static void onClientTick(Minecraft client) {
        if (waitingForRejoinCompletion) {
            if (client.level != null) {
                waitingForRejoinCompletion = false;
                rejoinCompletionTimeoutTicks = 0;
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
                OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, "REJOIN_RESULT failure timeout");
                handleRejoinFailure(client, worldToRejoin == null ? "" : worldToRejoin,
                        new IllegalStateException("Rejoin timed out after 30 seconds"));
            }
            return;
        }

        if (readyToRejoin && worldToRejoin != null) {
            if (client.getSingleplayerServer() != null) {
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

    public static void setWorldToRejoin(String levelId) {
        worldToRejoin = sanitizeLevelId(levelId);
    }

    public static String getWorldToRejoin() {
        return worldToRejoin;
    }

    public static void markReadyToRejoin() {
        if (worldToRejoin != null) {
            readyToRejoin = true;
        }
    }

    public static void clearReadyToRejoin() {
        readyToRejoin = false;
    }

    public static boolean isReadyToRejoin() {
        return readyToRejoin;
    }

    public static void resetRestoreState() {
        worldToRejoin = null;
        readyToRejoin = false;
        disconnectInitiated = false;
        waitingForRejoinCompletion = false;
        rejoinTickCounter = 0;
        retryCount = 0;
        disconnectWaitTicks = 0;
        rejoinCompletionTimeoutTicks = 0;
        HotRestoreState.reset();
    }

    private static void attemptAutoRejoin(Minecraft client, String levelId) {
        try {
            String normalized = sanitizeLevelId(levelId);
            if (normalized == null) {
                throw new IllegalArgumentException("Invalid level id for auto rejoin");
            }

            Component notice = Component.translatable("minebackup.message.restore.rejoining");
            client.setScreen(new SimpleMessageScreen(notice));

            if (client.level != null) {
                disconnectInitiated = true;
                disconnectWaitTicks = 0;
                try {
                    client.level.disconnect();
                } catch (Throwable t) {
                    MineBackup.LOGGER.warn("Failed to disconnect current level before restore: {}", t.getMessage());
                }
                try {
                    client.disconnect(new SimpleMessageScreen(notice));
                } catch (Throwable t) {
                    MineBackup.LOGGER.warn("Failed to open disconnect flow before restore: {}", t.getMessage());
                    client.setScreen(new SimpleMessageScreen(notice));
                }
                return;
            }

            startIntegratedServer(client, normalized);
        } catch (Exception e) {
            MineBackup.LOGGER.error("Auto rejoin failed for world '{}': {}", levelId, e.getMessage(), e);
            handleRejoinFailure(client, levelId, e);
        }
    }

    private static void startIntegratedServer(Minecraft client, String levelId) {
        try {
            String normalized = sanitizeLevelId(levelId);
            if (normalized == null) {
                throw new IllegalArgumentException("Invalid level id for integrated server start");
            }

            if (client.getSingleplayerServer() != null) {
                worldToRejoin = normalized;
                readyToRejoin = true;
                return;
            }

            waitingForRejoinCompletion = true;
            rejoinCompletionTimeoutTicks = 0;
            client.createWorldOpenFlows().openWorld(normalized, () -> {
                waitingForRejoinCompletion = false;
                OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, "REJOIN_RESULT failure cancelled");
                client.setScreen(new TitleScreen());
            });
        } catch (Exception e) {
            waitingForRejoinCompletion = false;
            handleRejoinFailure(client, levelId, e);
        }
    }

    private static void handleRejoinFailure(Minecraft client, String levelId, Exception error) {
        retryCount++;
        MineBackup.LOGGER.warn("Automatic rejoin attempt {}/{} failed for {}: {}",
                retryCount, MAX_RETRY_COUNT, levelId, error.getMessage());

        if (retryCount < MAX_RETRY_COUNT && sanitizeLevelId(levelId) != null) {
            worldToRejoin = sanitizeLevelId(levelId);
            readyToRejoin = true;
            return;
        }

        OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, "REJOIN_RESULT failure max_retries_exceeded");
        resetRestoreState();
        try {
            if (client.player != null) {
                client.player.sendSystemMessage(Component.translatable("minebackup.message.restore.failed"));
            }
        } catch (Exception ignored) {
        }
        try {
            client.setScreen(new SelectWorldScreen(new TitleScreen()));
        } catch (Exception ex) {
            MineBackup.LOGGER.warn("Failed to open world selection screen: {}", ex.getMessage());
            client.setScreen(new TitleScreen());
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

    private static class SimpleMessageScreen extends Screen {
        private final Component message;

        protected SimpleMessageScreen(Component message) {
            super(Component.empty());
            this.message = message;
        }

        @Override
        protected void init() {
            addRenderableWidget(Button.builder(Component.translatable("gui.back"), button ->
                    Minecraft.getInstance().setScreen(null)
            ).bounds(this.width / 2 - 100, this.height / 2 + 20, 200, 20).build());
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
