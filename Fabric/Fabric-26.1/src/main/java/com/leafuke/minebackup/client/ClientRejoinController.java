package com.leafuke.minebackup.client;

import com.leafuke.minebackup.MineBackup;
import com.leafuke.minebackup.config.Config;
import com.leafuke.minebackup.restore.RestoreSession;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.HttpUtil;
import net.minecraft.world.level.GameType;

public final class ClientRejoinController {
    private static final int REJOIN_DELAY_TICKS = 1;
    private static final int REJOIN_DEADLINE_TICKS = 500;
    private static final int LAN_REOPEN_INITIAL_DELAY_TICKS = 20;

    private static State state = State.IDLE;
    private static RestoreSession.RejoinInfo rejoinInfo;
    private static RestoreUiMessages uiMessages;
    private static int delayTicks;
    private static int elapsedTicks;
    private static java.util.function.BooleanSupplier currentSession = () -> true;
    private static java.util.function.BiConsumer<Boolean, String> completion = MineBackup::completeClientRestore;
    private static long clientGeneration;

    private static boolean pendingLanReopen;
    private static int lanReopenWaitTicks;
    private static int lanReopenAttempts;
    private static int lanReopenPort = -1;
    private static boolean randomFallbackTried;

    private ClientRejoinController() {
    }

    public static void requestRejoin(
            Minecraft client,
            RestoreSession.RejoinInfo info,
            RestoreUiMessages messages) {
        requestRejoin(client, info, messages, () -> true, MineBackup::completeClientRestore);
    }

    public static void requestRejoin(Minecraft client, RestoreSession.RejoinInfo info, RestoreUiMessages messages,
            java.util.function.BooleanSupplier current, java.util.function.BiConsumer<Boolean, String> finished) {
        client.execute(
                () -> {
                    if (!current.getAsBoolean()) return;
                    if (state != State.IDLE) {
                        MineBackup.LOGGER.warn("Rejected duplicate client rejoin request.");
                        return;
                    }
                    state = State.DELAYING;
                    currentSession = current;
                    completion = finished;
                    clientGeneration++;
                    String levelId = sanitizeLevelId(info.levelId());
                    if (levelId == null) {
                        finishFailure(client, "invalid_level_id");
                        return;
                    }

                    rejoinInfo =
                            new RestoreSession.RejoinInfo(
                                    levelId, info.reopenLan(), info.lanPort());
                    uiMessages = messages;
                    delayTicks = REJOIN_DELAY_TICKS;
                    elapsedTicks = 0;

                    if (client.level != null) {
                        finishFailure(client, "world_changed");
                        return;
                    }
                });
    }

    public static void restoreFailed(Minecraft client, Component message) {
        restoreFailed(client, message, () -> true);
    }

    public static void restoreFailed(Minecraft client, Component message, java.util.function.BooleanSupplier current) {
        client.execute(() -> {
            if (!current.getAsBoolean()) return;
            resetRejoinState();
            resetLanReopenState();
            if (client.player != null) {
                client.player.sendSystemMessage(message);
            } else {
                // Show the message and provide a way back to the world selection screen
                client.disconnect(new GenericMessageScreen(message), false);
                try {
                    client.setScreen(new SelectWorldScreen(new TitleScreen()));
                } catch (RuntimeException exception) {
                    MineBackup.LOGGER.warn("Failed to open world selection after restore failure", exception);
                    client.setScreen(new TitleScreen());
                }
            }
        });
    }

    public static void onClientTick(Minecraft client) {
        tickLanReopen(client);
        if (state == State.IDLE) {
            return;
        }

        if (!currentSession.getAsBoolean()) {
            var abandoned = completion;
            resetRejoinState();
            resetLanReopenState();
            abandoned.accept(false, "world_changed");
            return;
        }
        elapsedTicks++;
        if (elapsedTicks >= REJOIN_DEADLINE_TICKS) {
            finishFailure(client, "timeout");
            return;
        }

        if (state == State.OPENING) {
            if (client.level != null && client.player != null) {
                var joined=client.getSingleplayerServer();
                if(joined==null || rejoinInfo==null || !joined.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                        .toAbsolutePath().normalize().equals(client.getLevelSource().getBaseDir().resolve(rejoinInfo.levelId()).toAbsolutePath().normalize())){
                    var finish=completion;
                    resetRejoinState();resetLanReopenState();
                    finish.accept(false,"world_changed");
                }else finishSuccess(client);
            }
            return;
        }

        if (client.getSingleplayerServer() != null) {
            return;
        }
        if (delayTicks > 0) {
            delayTicks--;
            return;
        }
        attemptOpenWorld(client);
    }

    private static void attemptOpenWorld(Minecraft client) {
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
        state = State.OPENING;
        client.setScreen(new GenericMessageScreen(uiMessages.rejoining()));
        try {
            long generation = clientGeneration;
            WorldOpenGate.automatic(
                    () ->
                            client.createWorldOpenFlows()
                                    .openWorld(
                                            levelId,
                                            () ->
                                                    client.execute(
                                                            () -> {
                                                                if (clientGeneration == generation
                                                                        && currentSession
                                                                                .getAsBoolean())
                                                                    finishFailure(
                                                                            client, "cancelled");
                                                            })));
        } catch (RuntimeException exception) {
            MineBackup.LOGGER.warn("Opening the restored world failed", exception);

            finishFailure(client, "open_failed");
        }
    }

    private static void finishSuccess(Minecraft client) {
        if (!currentSession.getAsBoolean()) {
            var abandoned = completion;
            resetRejoinState();
            resetLanReopenState();
            abandoned.accept(false, "world_changed");
            return;
        }
        if (state != State.OPENING) {
            return;
        }
        RestoreSession.RejoinInfo completedInfo = rejoinInfo;
        Component successMessage = uiMessages == null
                ? Component.translatable("minebackup.message.restore.success_overlay")
                : uiMessages.succeeded();
        var finished = completion;
        resetRejoinState();
        if (completedInfo != null) {
            scheduleLanReopen(completedInfo);
        }
        finished.accept(true, null);
        if (client.player != null) {
            client.player.sendSystemMessage(successMessage);
        }
    }

    private static void finishFailure(Minecraft client, String reason) {
        if (!currentSession.getAsBoolean()) {
            var abandoned = completion;
            resetRejoinState();
            resetLanReopenState();
            abandoned.accept(false, "world_changed");
            return;
        }
        if (state == State.IDLE) {
            return;
        }
        var finished = completion;
        resetRejoinState();
        resetLanReopenState();
        finished.accept(false, reason);

        // Show user-friendly error message
        Component errorMessage = Component.translatable(
                "minebackup.message.restore.rejoin_error." + reason);
        if (client.player != null) {
            client.player.sendSystemMessage(errorMessage);
        }

        try {
            client.setScreen(new SelectWorldScreen(new TitleScreen()));
        } catch (RuntimeException exception) {
            MineBackup.LOGGER.warn("Failed to open world selection after restore failure", exception);
            client.setScreen(new TitleScreen());
        }
    }

    private static void resetRejoinState() {
        clientGeneration++;
        currentSession = () -> true;
        completion = MineBackup::completeClientRestore;
        state = State.IDLE;
        rejoinInfo = null;
        uiMessages = null;
        delayTicks = 0;
        elapsedTicks = 0;
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

    private static void tickLanReopen(Minecraft client) {
        if (!pendingLanReopen || client.level == null) {
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

        if (publishLan(server, targetPort)) {
            resetLanReopenState();
            sendClientMessage(client, Component.translatable(
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
            int fallbackPort = HttpUtil.getAvailablePort();
            if (fallbackPort > 0 && fallbackPort != lanReopenPort) {
                lanReopenPort = fallbackPort;
                lanReopenAttempts = 0;
                randomFallbackTried = true;
                lanReopenWaitTicks = config.retryIntervalTicks();
                sendClientMessage(client, Component.translatable(
                        "minebackup.message.lan.reopen.fallback",
                        fallbackPort));
                return;
            }
        }
        finishLanReopenFailure(client);
    }

    private static boolean publishLan(IntegratedServer server, int port) {
        try {
            GameType gameType = server.getDefaultGameType();
            boolean allowCommands = server.getPlayerList().isAllowCommandsForAllPlayers();
            return server.publishServer(gameType, allowCommands, port);
        } catch (RuntimeException exception) {
            MineBackup.LOGGER.warn("Failed to reopen LAN after restore", exception);
            return false;
        }
    }

    private static void finishLanReopenFailure(Minecraft client) {
        resetLanReopenState();
        sendClientMessage(client, Component.translatable("minebackup.message.lan.reopen.failed"));
    }

    private static void sendClientMessage(Minecraft client, Component message) {
        if (client.player != null) {
            client.player.sendSystemMessage(message);
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
}
