package com.leafuke.minebackup.runtime;

import com.leafuke.minebackup.MineBackup;
import net.minecraft.server.MinecraftServer;

public final class LocalSaveCoordinator {
    private LocalSaveCoordinator() {
    }

    public static boolean save(MinecraftServer server) {
        if (server == null) {
            return false;
        }

        boolean playersSaved = true;
        try {
            server.getPlayerList().saveAll();
        } catch (RuntimeException exception) {
            playersSaved = false;
            MineBackup.LOGGER.error("Failed to save player data before MineBackup operation", exception);
        }

        try {
            boolean worldSaved = server.saveAllChunks(true, true, true);
            return playersSaved && worldSaved;
        } catch (RuntimeException exception) {
            MineBackup.LOGGER.error("Failed to save world data before MineBackup operation", exception);
            return false;
        }
    }
}
