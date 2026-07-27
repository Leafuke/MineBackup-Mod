package com.leafuke.minebackup;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;

public final class ModInfo {
    private ModInfo() {
    }

    public static String version() {
        return ModList.get()
                .getModContainerById(MineBackup.MOD_ID)
                .map(ModContainer::getModInfo)
                .map(info -> info.getVersion().toString())
                .orElse("unknown");
    }
}
