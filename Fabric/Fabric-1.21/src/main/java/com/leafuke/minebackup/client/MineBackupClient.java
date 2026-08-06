package com.leafuke.minebackup.client;

import com.leafuke.minebackup.MineBackup;
import com.leafuke.minebackup.config.Config;
import com.leafuke.minebackup.compat.TextEvents;
import com.leafuke.minebackup.restore.RestoreSession;
import com.leafuke.minebackup.update.UpdateChecker;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.MutableText;

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
                MinecraftClient client = MinecraftClient.getInstance();
                if (client != null) {
                    ClientRejoinController.requestRejoin(client, info, messages);
                }
            }

            @Override
            public void restoreFailed(Text message) {
                MinecraftClient client = MinecraftClient.getInstance();
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

    private void onClientTick(MinecraftClient client) {
        showUpdateMessage(client);
        LanAutoReconnectController.onClientTick(client);
        ClientRejoinController.onClientTick(client);
    }

    private void showUpdateMessage(MinecraftClient client) {
        if (updatePromptShown || client.player == null || client.world == null) {
            return;
        }
        UpdateChecker.Result result = updateResult.get();
        if (result == null) {
            return;
        }

        ClickEvent clickEvent = TextEvents.openUrl(result.releaseUri());
        HoverEvent hoverEvent = TextEvents.showText(
                Text.translatable("minebackup.message.update.hover"));
        MutableText message = Text.translatable(
                        "minebackup.message.update.available",
                        result.latestVersion())
                .styled(style -> {
                    if (clickEvent != null) {
                        style = style.withClickEvent(clickEvent);
                    }
                    if (hoverEvent != null) {
                        style = style.withHoverEvent(hoverEvent);
                    }
                    return style;
                });
        client.inGameHud.getChatHud().addMessage(message);
        updatePromptShown = true;
    }
}
