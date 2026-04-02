package com.leafuke.minebackup;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

public class MineBackupClient implements ClientModInitializer {
    private static final UpdateChecker UPDATE_CHECKER = new UpdateChecker();
    private static boolean updatePromptShown = false;

    @Override
    public void onInitializeClient() {
        Config.load();
        UPDATE_CHECKER.start();
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    private void onClientTick(MinecraftClient client) {
        tryShowUpdateMessage(client);
        LanAutoReconnectController.onClientTick(client);
        ClientRejoinController.onClientTick(client);
    }

    public static void setWorldToRejoin(String levelId) {
        ClientRejoinController.setWorldToRejoin(levelId);
    }

    public static String getWorldToRejoin() {
        return ClientRejoinController.getWorldToRejoin();
    }

    public static void markReadyToRejoin() {
        ClientRejoinController.markReadyToRejoin();
    }

    public static void clearReadyToRejoin() {
        ClientRejoinController.clearReadyToRejoin();
    }

    public static boolean isReadyToRejoin() {
        return ClientRejoinController.isReadyToRejoin();
    }

    public static void resetRestoreState() {
        ClientRejoinController.resetRestoreState();
    }

    public static void showRestoreSuccessOverlay() {
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null) {
                return;
            }
            client.execute(() -> {
                if (client.player != null) {
                    client.player.sendMessage(Text.translatable("minebackup.message.restore.success_overlay"), true);
                }
            });
        } catch (Exception ignored) {
        }
    }

    private void tryShowUpdateMessage(MinecraftClient client) {
        if (updatePromptShown || client == null || client.player == null || client.world == null) {
            return;
        }
        if (!UPDATE_CHECKER.needUpdate || UPDATE_CHECKER.latestVersion == null || UPDATE_CHECKER.latestReleaseUrl == null) {
            return;
        }

        MutableText message = Text.translatable("minebackup.message.update.available", UPDATE_CHECKER.latestVersion)
                .styled(style -> style
                        .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, UPDATE_CHECKER.latestReleaseUrl))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Text.translatable("minebackup.message.update.hover"))));
        client.player.sendMessage(message, false);
        updatePromptShown = true;
    }
}
