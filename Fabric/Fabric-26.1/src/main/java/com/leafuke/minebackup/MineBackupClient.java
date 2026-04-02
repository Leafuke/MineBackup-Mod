package com.leafuke.minebackup;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public class MineBackupClient implements ClientModInitializer {
    private static final UpdateChecker UPDATE_CHECKER = new UpdateChecker();
    private static boolean updatePromptShown = false;

    @Override
    public void onInitializeClient() {
        Config.load();
        UPDATE_CHECKER.start();
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    private void onClientTick(Minecraft client) {
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
            Minecraft client = Minecraft.getInstance();
            if (client == null) {
                return;
            }
            client.execute(() -> {
                if (client.player != null) {
                    client.player.sendSystemMessage(Component.translatable("minebackup.message.restore.success_overlay"));
                }
            });
        } catch (Exception ignored) {
        }
    }

    private void tryShowUpdateMessage(Minecraft client) {
        if (updatePromptShown || client == null || client.player == null || client.level == null) {
            return;
        }
        if (!UPDATE_CHECKER.needUpdate || UPDATE_CHECKER.latestVersion == null || UPDATE_CHECKER.latestReleaseUrl == null) {
            return;
        }

        MutableComponent message = Component.translatable("minebackup.message.update.available", UPDATE_CHECKER.latestVersion)
                .withStyle(style -> style
                        .withClickEvent(new net.minecraft.network.chat.ClickEvent.OpenUrl(java.net.URI.create(UPDATE_CHECKER.latestReleaseUrl)))
                        .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(
                                Component.translatable("minebackup.message.update.hover"))));
        client.gui.getChat().addClientSystemMessage(message);
        updatePromptShown = true;
    }
}
