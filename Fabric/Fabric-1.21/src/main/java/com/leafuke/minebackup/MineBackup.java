package com.leafuke.minebackup;

import com.leafuke.minebackup.command.Command;
import com.leafuke.minebackup.api.v2.MineBackupApi;
import com.leafuke.minebackup.api.v2.CurrentWorldAutomationMode;
import com.leafuke.minebackup.compat.TextEvents;
import com.leafuke.minebackup.knotlink.KnotLinkClient;
import com.leafuke.minebackup.runtime.MineBackupRuntime;
import com.leafuke.minebackup.runtime.AutoBackupUpdateResult;
import com.leafuke.minebackup.runtime.AutomationUpdateResult;
import com.leafuke.minebackup.runtime.RestoreControlResult;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.text.Text;
import net.minecraft.text.MutableText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Duration;

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

    public static MineBackupApi api() {
        return RUNTIME;
    }

    public static RestoreControlResult confirmPendingRestore() {
        return RUNTIME.confirmPendingRestore();
    }

    public static RestoreControlResult cancelPendingRestore() {
        return RUNTIME.cancelPendingRestore();
    }

    public static AutoBackupUpdateResult startAutomaticBackup(Duration interval) {
        return RUNTIME.startAutomaticBackup(interval);
    }

    public static AutoBackupUpdateResult stopAutomaticBackup() {
        return RUNTIME.stopAutomaticBackup();
    }

    public static AutomationUpdateResult startCurrentWorldAutomation(
            Duration interval,
            CurrentWorldAutomationMode mode) {
        return RUNTIME.startCurrentWorldAutomation(interval, mode);
    }

    public static AutomationUpdateResult stopCurrentWorldAutomation() {
        return RUNTIME.stopCurrentWorldAutomation();
    }

    public static void completeClientRestore(boolean success, String reason) {
        RUNTIME.completeClientRestore(success, reason);
    }

    public static void shutdownClient() {
        RUNTIME.close();
    }

    public static MutableText pluginLinkMessage() {
        return Text.translatable("minebackup.message.plugin_link_prefix")
                .append(Text.literal(PLUGIN_GUIDE_URL).styled(style -> style
                        .withClickEvent(TextEvents.openUrl(URI.create(PLUGIN_GUIDE_URL)))
                        .withHoverEvent(TextEvents.showText(
                                Text.translatable("minebackup.message.plugin_link_hover")))
                        .withUnderline(true)));
    }
}
