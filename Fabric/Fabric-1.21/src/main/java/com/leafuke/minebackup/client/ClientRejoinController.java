package com.leafuke.minebackup.client;

import com.leafuke.minebackup.MineBackup;
import com.leafuke.minebackup.config.Config;
import com.leafuke.minebackup.knotlink.protocol.KnotLinkRequest;
import com.leafuke.minebackup.restore.RestoreSession;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.MessageScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.world.GameMode;

public final class ClientRejoinController {
    private static final int REJOIN_DELAY_TICKS = 40;
    private static final int REJOIN_DEADLINE_TICKS = 500;
    private static final int MAX_SYNCHRONOUS_ATTEMPTS = 3;
    private static final int LAN_REOPEN_INITIAL_DELAY_TICKS = 20;

    private static State state = State.IDLE;
    private static RestoreSession.RejoinInfo rejoinInfo;
    private static RestoreUiMessages uiMessages;
    private static int delayTicks;
    private static int elapsedTicks;
    private static int attempts;
    private static boolean resultReported;

    private static boolean pendingLanReopen;
    private static int lanReopenWaitTicks;
    private static int lanReopenAttempts;
    private static int lanReopenPort = -1;
    private static boolean randomFallbackTried;

    private ClientRejoinController() {
    }

    public static void requestRejoin(
            MinecraftClient client,
            RestoreSession.RejoinInfo info,
            RestoreUiMessages messages) {
        client.execute(() -> {
            if (state != State.IDLE) {
                MineBackup.LOGGER.warn("Rejected duplicate client rejoin request.");
                return;
            }
            state = State.DELAYING;
            resultReported = false;
            String levelId = sanitizeLevelId(info.levelId());
            if (levelId == null) {
                finishFailure(client, "invalid_level_id");
                return;
            }

            rejoinInfo = new RestoreSession.RejoinInfo(levelId, info.reopenLan(), info.lanPort());
            uiMessages = messages;
            delayTicks = REJOIN_DELAY_TICKS;
            elapsedTicks = 0;
            attempts = 0;

            if (client.world != null) {
                Text notice = uiMessages.rejoining();
                client.disconnect(new MessageScreen(notice), false);
                delayTicks = 20;
            }
        });
    }

    public static void restoreFailed(MinecraftClient client, Text message) {
        client.execute(() -> {
            resetRejoinState();
            resetLanReopenState();
            if (client.player != null) {
                client.player.sendMessage(message);
            } else {
                client.setScreen(new MessageScreen(message));
            }
        });
    }

    public static void onClientTick(MinecraftClient client) {
        tickLanReopen(client);
        if (state == State.IDLE) {
            return;
        }

        elapsedTicks++;
        if (elapsedTicks >= REJOIN_DEADLINE_TICKS) {
            finishFailure(client, "timeout");
            return;
        }

        if (state == State.OPENING) {
            if (client.world != null && client.player != null) {
                finishSuccess(client);
            }
            return;
        }

        if (client.getServer() != null) {
            return;
        }
        if (delayTicks > 0) {
            delayTicks--;
            return;
        }
        attemptOpenWorld(client);
    }

    private static void attemptOpenWorld(MinecraftClient client) {
        RestoreSession.RejoinInfo info = rejoinInfo;
        if (info == null) {
            finishFailure(client, "missing_session");
            return;
        }

        String levelId = sanitizeLevelId(info.levelId());
        if (levelId == null) {
            finishFailure(client, "invalid_level_id");
            return;
        }

        attempts++;
        state = State.OPENING;
        client.setScreen(new MessageScreen(uiMessages.rejoining()));
        try {
            client.createIntegratedServerLoader().start(levelId, () ->
                    client.execute(() -> finishFailure(client, "cancelled")));
        } catch (RuntimeException exception) {
            MineBackup.LOGGER.warn(
                    "Synchronous world rejoin attempt {}/{} failed",
                    attempts,
                    MAX_SYNCHRONOUS_ATTEMPTS,
                    exception);
            if (attempts < MAX_SYNCHRONOUS_ATTEMPTS
                    && elapsedTicks + REJOIN_DELAY_TICKS < REJOIN_DEADLINE_TICKS) {
                state = State.DELAYING;
                delayTicks = REJOIN_DELAY_TICKS;
                return;
            }
            finishFailure(client, "max_retries_exceeded");
        }
    }

    private static void finishSuccess(MinecraftClient client) {
        if (state != State.OPENING) {
            return;
        }
        RestoreSession.RejoinInfo completedInfo = rejoinInfo;
        Text successMessage = uiMessages == null
                ? Text.translatable("minebackup.message.restore.success_overlay")
                : uiMessages.succeeded();
        reportResult("success", null);
        resetRejoinState();
        if (completedInfo != null) {
            scheduleLanReopen(completedInfo);
        }
        MineBackup.completeClientRestore(true, null);
        if (client.player != null) {
            client.player.sendMessage(successMessage);
        }
    }

    private static void finishFailure(MinecraftClient client, String reason) {
        if (state == State.IDLE) {
            return;
        }
        reportResult("failure", reason);
        resetRejoinState();
        resetLanReopenState();
        MineBackup.completeClientRestore(false, reason);
        try {
            client.setScreen(new SelectWorldScreen(new TitleScreen()));
        } catch (RuntimeException exception) {
            MineBackup.LOGGER.warn("Failed to open world selection after restore failure", exception);
            client.setScreen(new TitleScreen());
        }
    }

    private static void reportResult(String result, String reason) {
        if (resultReported) {
            return;
        }
        resultReported = true;
        KnotLinkRequest request = KnotLinkRequest.command("REJOIN_RESULT")
                .conversation()
                .field("result", result);
        if (reason != null && !reason.isBlank()) {
            request.field("reason", reason);
        }
        MineBackup.knotLink().query(request).whenComplete((response, error) -> {
            if (error != null) {
                MineBackup.LOGGER.warn("Failed to report rejoin result", error);
            } else if (!response.isOk()) {
                MineBackup.LOGGER.warn("Backend rejected rejoin result: {}", response.displayMessage());
            }
        });
    }

    private static void resetRejoinState() {
        state = State.IDLE;
        rejoinInfo = null;
        uiMessages = null;
        delayTicks = 0;
        elapsedTicks = 0;
        attempts = 0;
    }

    private static void scheduleLanReopen(RestoreSession.RejoinInfo info) {
        Config.HostReopen config = Config.get().hostReopen();
        if (!config.enabled() || !info.reopenLan()) {
            resetLanReopenState();
            return;
        }
        pendingLanReopen = true;
        lanReopenPort = info.lanPort();
        lanReopenWaitTicks = LAN_REOPEN_INITIAL_DELAY_TICKS;
        lanReopenAttempts = 0;
        randomFallbackTried = false;
    }

    private static void tickLanReopen(MinecraftClient client) {
        if (!pendingLanReopen || client.world == null) {
            return;
        }
        IntegratedServer server = client.getServer();
        if (server == null) {
            return;
        }
        if (server.isRemote()) {
            resetLanReopenState();
            return;
        }
        if (lanReopenWaitTicks > 0) {
            lanReopenWaitTicks--;
            return;
        }

        int targetPort = lanReopenPort;
        if (targetPort <= 0) {
            targetPort = findAvailablePort();
            lanReopenPort = targetPort;
        }
        if (targetPort <= 0) {
            finishLanReopenFailure(client);
            return;
        }

        if (publishLan(server, targetPort)) {
            resetLanReopenState();
            sendClientMessage(client, Text.translatable(
                    "minebackup.message.lan.reopen.success",
                    targetPort));
            return;
        }

        Config.HostReopen config = Config.get().hostReopen();
        lanReopenAttempts++;
        if (lanReopenAttempts < config.retryCount()) {
            lanReopenWaitTicks = config.retryIntervalTicks();
            return;
        }
        if (!randomFallbackTried && config.allowRandomPortFallback()) {
            int fallbackPort = findAvailablePort();
            if (fallbackPort > 0 && fallbackPort != lanReopenPort) {
                lanReopenPort = fallbackPort;
                lanReopenAttempts = 0;
                randomFallbackTried = true;
                lanReopenWaitTicks = config.retryIntervalTicks();
                sendClientMessage(client, Text.translatable(
                        "minebackup.message.lan.reopen.fallback",
                        fallbackPort));
                return;
            }
        }
        finishLanReopenFailure(client);
    }

    private static boolean publishLan(IntegratedServer server, int port) {
        try {
            GameMode gameType = server.getDefaultGameMode();
            boolean allowCommands = server.getPlayerManager().areCheatsAllowed();
            return server.openToLan(gameType, allowCommands, port);
        } catch (RuntimeException exception) {
            MineBackup.LOGGER.warn("Failed to reopen LAN after restore", exception);
            return false;
        }
    }

    private static void finishLanReopenFailure(MinecraftClient client) {
        resetLanReopenState();
        sendClientMessage(client, Text.translatable("minebackup.message.lan.reopen.failed"));
    }

    private static void sendClientMessage(MinecraftClient client, Text message) {
        if (client.player != null) {
            client.player.sendMessage(message);
        }
    }

    private static void resetLanReopenState() {
        pendingLanReopen = false;
        lanReopenWaitTicks = 0;
        lanReopenAttempts = 0;
        lanReopenPort = -1;
        randomFallbackTried = false;
    }

    private static String sanitizeLevelId(String rawLevelId) {
        if (rawLevelId == null) {
            return null;
        }
        String normalized = rawLevelId.trim();
        if (normalized.isEmpty()
                || ".".equals(normalized)
                || "..".equals(normalized)
                || normalized.contains("/")
                || normalized.contains("\\")) {
            return null;
        }
        return normalized;
    }

    private enum State {
        IDLE,
        DELAYING,
        OPENING
    }

    private static int findAvailablePort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (java.io.IOException e) {
            return -1;
        }
    }

}