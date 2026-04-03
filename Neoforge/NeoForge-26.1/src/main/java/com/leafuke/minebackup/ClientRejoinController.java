package com.leafuke.minebackup;

import com.leafuke.minebackup.knotlink.OpenSocketQuerier;
import com.leafuke.minebackup.restore.HotRestoreState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.HttpUtil;
import net.minecraft.world.level.GameType;

public final class ClientRejoinController {
    private static final String QUERIER_APP_ID = "0x00000020";
    private static final String QUERIER_SOCKET_ID = "0x00000010";
    private static final int REJOIN_DELAY_TICKS = 40;
    private static final int MAX_RETRY_COUNT = 5;
    private static final int DISCONNECT_WAIT_TICKS = 20;
    private static final int REJOIN_COMPLETION_TIMEOUT_TICKS = 600;
    private static final int LAN_REOPEN_INITIAL_DELAY_TICKS = 20;

    private static volatile String worldToRejoin;
    private static volatile boolean readyToRejoin;
    private static volatile boolean disconnectInitiated;
    private static volatile boolean waitingForRejoinCompletion;

    private static int rejoinTickCounter;
    private static int retryCount;
    private static int disconnectWaitTicks;
    private static int rejoinCompletionTimeoutTicks;
    private static volatile boolean pendingLanReopen;
    private static int lanReopenWaitTicks;
    private static int lanReopenAttemptCount;
    private static int lanReopenPort = -1;
    private static boolean lanReopenTriedRandomFallback;

    private ClientRejoinController() {
    }

    public static void onClientTick(Minecraft client) {
        tickPendingLanReopen(client);

        if (waitingForRejoinCompletion) {
            if (client.level != null) {
                waitingForRejoinCompletion = false;
                rejoinCompletionTimeoutTicks = 0;
                OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, "REJOIN_RESULT success");
                retryCount = 0;
                worldToRejoin = null;
                scheduleLanReopenAfterRestore(client);
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
        resetLanReopenState();
        HotRestoreState.reset();
    }

    private static void tickPendingLanReopen(Minecraft client) {
        if (!pendingLanReopen) {
            return;
        }
        if (client == null || client.level == null) {
            return;
        }

        IntegratedServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        if (server.isPublished()) {
            resetLanReopenState();
            return;
        }

        if (lanReopenWaitTicks > 0) {
            lanReopenWaitTicks--;
            return;
        }

        int targetPort = lanReopenPort;
        if (targetPort <= 0) {
            targetPort = HttpUtil.getAvailablePort();
            lanReopenPort = targetPort;
        }
        if (targetPort <= 0) {
            finishLanReopenFailure(client);
            return;
        }

        if (tryPublishLan(server, targetPort)) {
            resetLanReopenState();
            showClientTip(client, Component.translatable("minebackup.message.lan.reopen.success", targetPort));
            return;
        }

        lanReopenAttemptCount++;
        if (lanReopenAttemptCount < Config.getLanReopenRetryCount()) {
            lanReopenWaitTicks = Config.getLanReopenRetryIntervalTicks();
            return;
        }

        if (!lanReopenTriedRandomFallback && Config.isLanReopenAllowRandomPortFallback()) {
            int fallbackPort = HttpUtil.getAvailablePort();
            if (fallbackPort > 0 && fallbackPort != lanReopenPort) {
                lanReopenPort = fallbackPort;
                lanReopenAttemptCount = 0;
                lanReopenTriedRandomFallback = true;
                lanReopenWaitTicks = Config.getLanReopenRetryIntervalTicks();
                showClientTip(client, Component.translatable("minebackup.message.lan.reopen.fallback", fallbackPort));
                return;
            }
        }

        finishLanReopenFailure(client);
    }

    private static void scheduleLanReopenAfterRestore(Minecraft client) {
        if (!Config.isAutoReopenLanAfterRestore() || !HotRestoreState.reopenLanAfterRestore) {
            resetLanReopenState();
            return;
        }

        int preferredPort = HotRestoreState.lastLanPort;
        if (preferredPort <= 0) {
            IntegratedServer server = client == null ? null : client.getSingleplayerServer();
            preferredPort = server == null ? -1 : server.getPort();
        }

        pendingLanReopen = true;
        lanReopenPort = preferredPort;
        lanReopenWaitTicks = LAN_REOPEN_INITIAL_DELAY_TICKS;
        lanReopenAttemptCount = 0;
        lanReopenTriedRandomFallback = false;
    }

    private static boolean tryPublishLan(IntegratedServer server, int port) {
        try {
            GameType gameType = server.getDefaultGameType();
            boolean allowCommands = server.getPlayerList() != null && server.getPlayerList().isAllowCommandsForAllPlayers();
            return server.publishServer(gameType, allowCommands, port);
        } catch (Exception e) {
            MineBackup.LOGGER.warn("Failed to reopen LAN after restore: {}", e.getMessage());
            return false;
        }
    }

    private static void finishLanReopenFailure(Minecraft client) {
        resetLanReopenState();
        showClientTip(client, Component.translatable("minebackup.message.lan.reopen.failed"));
    }

    private static void resetLanReopenState() {
        pendingLanReopen = false;
        lanReopenWaitTicks = 0;
        lanReopenAttemptCount = 0;
        lanReopenPort = -1;
        lanReopenTriedRandomFallback = false;
    }

    private static void showClientTip(Minecraft client, Component message) {
        if (client == null || message == null) {
            return;
        }
        client.execute(() -> {
            if (client.player != null) {
                client.player.sendSystemMessage(message);
            }
        });
    }

    private static void attemptAutoRejoin(Minecraft client, String levelId) {
        try {
            String normalized = sanitizeLevelId(levelId);
            if (normalized == null) {
                throw new IllegalArgumentException("Invalid level id for auto rejoin");
            }

            Component notice = Component.translatable("minebackup.message.restore.rejoining");
            Screen messageScreen = new GenericMessageScreen(notice);
            client.setScreen(messageScreen);

            if (client.level != null) {
                disconnectInitiated = true;
                disconnectWaitTicks = 0;
                try {
                    client.level.disconnect(notice);
                } catch (Throwable t) {
                    MineBackup.LOGGER.warn("Failed to disconnect current level before restore: {}", t.getMessage());
                }
                try {
                    client.disconnect(messageScreen, false);
                } catch (Throwable t) {
                    MineBackup.LOGGER.warn("Failed to open disconnect flow before restore: {}", t.getMessage());
                    client.setScreen(messageScreen);
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
                resetRestoreState();
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

        String normalized = sanitizeLevelId(levelId);
        if (retryCount < MAX_RETRY_COUNT && normalized != null) {
            worldToRejoin = normalized;
            readyToRejoin = true;
            return;
        }

        OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, "REJOIN_RESULT failure max_retries_exceeded");
        resetRestoreState();
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
}
