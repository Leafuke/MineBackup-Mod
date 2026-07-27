package com.leafuke.minebackup.client;

import com.leafuke.minebackup.MineBackup;
import com.leafuke.minebackup.config.Config;
import com.leafuke.minebackup.restore.RestoreSession;
import com.leafuke.minebackup.update.UpdateChecker;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

import java.util.concurrent.atomic.AtomicReference;

@EventBusSubscriber(modid = MineBackup.MOD_ID, value = Dist.CLIENT)
public final class MineBackupClient {
    private static final AtomicReference<UpdateChecker.Result> updateResult = new AtomicReference<>();
    private static boolean updatePromptShown;

    public static void initialize() {
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
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        showUpdateMessage(client);
        LanAutoReconnectController.onClientTick(client);
        ClientRejoinController.onClientTick(client);
    }

    @SubscribeEvent
    public static void onGameShuttingDown(GameShuttingDownEvent event) {
        MineBackup.shutdownClient();
    }

    private static void showUpdateMessage(Minecraft client) {
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
        client.gui.getChat().addClientSystemMessage(message);
        updatePromptShown = true;
    }
}
