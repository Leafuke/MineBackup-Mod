package com.leafuke.minebackup;

import com.leafuke.minebackup.knotlink.KnotLinkClient;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;

public final class MineBackup implements ModInitializer {
    public static final String MOD_ID = "minebackup";
    public static final String PLUGIN_GUIDE_URL = "https://modrinth.com/plugin/minebackupplugin";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final MineBackupRuntime RUNTIME = new MineBackupRuntime();

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> Command.register(dispatcher));
        RUNTIME.registerEvents();
        LOGGER.info("MineBackup {} initialized with KnotLink v2.", ModInfo.version());
    }

    public static KnotLinkClient knotLink() {
        return RUNTIME.knotLink();
    }

    public static void completeClientRestore() {
        RUNTIME.completeClientRestore();
    }

    public static void shutdownClient() {
        RUNTIME.close();
    }

    public static MutableComponent pluginLinkMessage() {
        return Component.translatable("minebackup.message.plugin_link_prefix")
                .append(Component.literal(PLUGIN_GUIDE_URL).withStyle(style -> style
                        .withClickEvent(new net.minecraft.network.chat.ClickEvent.OpenUrl(
                                URI.create(PLUGIN_GUIDE_URL)))
                        .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(
                                Component.translatable("minebackup.message.plugin_link_hover")))
                        .withUnderlined(true)));
    }
}
