package com.leafuke.minebackup;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

public class MineBackupClient {
    private static final UpdateChecker UPDATE_CHECKER = new UpdateChecker();
    private static boolean updatePromptShown = false;

    public static void initialize() {
        UPDATE_CHECKER.start();
        NeoForge.EVENT_BUS.register(new MineBackupClient());
    }

    @SubscribeEvent
    public void onClientTick(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        tryShowUpdateMessage(client);
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
                    client.player.displayClientMessage(Component.translatable("minebackup.message.restore.success_overlay"), true);
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
                        .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, UPDATE_CHECKER.latestReleaseUrl))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.translatable("minebackup.message.update.hover"))));
        client.player.sendSystemMessage(message);
        updatePromptShown = true;
    }
}
