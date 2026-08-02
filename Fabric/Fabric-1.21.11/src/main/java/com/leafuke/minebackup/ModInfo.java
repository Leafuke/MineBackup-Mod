package com.leafuke.minebackup;

import net.fabricmc.loader.api.FabricLoader;

public final class ModInfo {
    private ModInfo() {
    }

    public static String version() {
        return FabricLoader.getInstance()
                .getModContainer(MineBackup.MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }
}
