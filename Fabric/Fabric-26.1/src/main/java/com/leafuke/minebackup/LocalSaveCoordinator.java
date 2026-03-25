package com.leafuke.minebackup;

import net.minecraft.server.MinecraftServer;

public final class LocalSaveCoordinator {
    private LocalSaveCoordinator() {
    }

    public static boolean saveForLocalCommand(MinecraftServer server) {
        if (server == null) {
            return false;
        }

        try {
            server.getPlayerList().saveAll();
        } catch (Throwable t) {
            MineBackup.LOGGER.warn("Failed to save player data before local save: {}", t.getMessage());
        }

        try {
            server.saveAllChunks(true, true, true);
            return true;
        } catch (Throwable t) {
            MineBackup.LOGGER.warn("Failed to save world data for local command: {}", t.getMessage());
            return false;
        }
    }
}
