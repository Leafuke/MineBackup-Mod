package com.leafuke.minebackup.client;

import com.leafuke.minebackup.MineBackup;
import com.leafuke.minebackup.config.Config;
import com.leafuke.minebackup.restore.RestoreSession;
import com.leafuke.minebackup.update.UpdateChecker;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.concurrent.atomic.AtomicReference;

public final class MineBackupClient implements ClientModInitializer {
    private final AtomicReference<UpdateChecker.Result> updateResult = new AtomicReference<>();
    private boolean updatePromptShown;

    @Override
    public void onInitializeClient() {
        Config.Snapshot config = Config.load();
        ClientHooks.register(new ClientHooks.Handler() {
            @Override
            public void requestRejoin(RestoreSession.RejoinInfo info, RestoreUiMessages messages) {
                Minecraft client = Minecraft.getInstance();
                if (client != null) {
                    ClientRejoinController.requestRejoin(client, info, messages);
                }
            }

            @Override
            public void restoreFailed(Component message) {
                Minecraft client = Minecraft.getInstance();
                if (client != null) {
                    ClientRejoinController.restoreFailed(client, message);
                }
            }
        });

        if (config.updateCheckEnabled()) {
            new UpdateChecker().check().thenAccept(result -> result
                    .filter(UpdateChecker.Result::updateAvailable)
                    .ifPresent(updateResult::set));
        } else {
            MineBackup.LOGGER.info("MineBackup update check is disabled.");
        }

        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> MineBackup.shutdownClient());
    }

    private void onClientTick(Minecraft client) {
        showUpdateMessage(client);
        LanAutoReconnectController.onClientTick(client);
        ClientRejoinController.onClientTick(client);
    }

    private void showUpdateMessage(Minecraft client) {
        if (updatePromptShown || client.player == null || client.level == null) {
            return;
        }
        UpdateChecker.Result result = updateResult.get();
        if (result == null) {
            return;
        }

        MutableComponent message = Component.translatable(
                        "minebackup.message.update.available",
                        result.latestVersion())
                .withStyle(style -> style
                        .withClickEvent(new net.minecraft.network.chat.ClickEvent.OpenUrl(result.releaseUri()))
                        .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(
                                Component.translatable("minebackup.message.update.hover"))));
        client.gui.hud.getChat().addClientSystemMessage(message);
        updatePromptShown = true;
    }
}
