package com.leafuke.minebackup.runtime;

import com.leafuke.minebackup.MineBackup;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;

public final class AutoSaveController {
    private final List<ServerLevel> frozenLevels = new ArrayList<>();
    private boolean frozen;
    private Object owner;

    public synchronized boolean freeze(MinecraftServer server) {
        return freeze(server, this);
    }

    public synchronized boolean freeze(MinecraftServer server, Object token) {
        if (frozen) {
            MineBackup.LOGGER.warn("Ignoring duplicate request to freeze auto-save.");
            return false;
        }

        frozenLevels.clear();
        for (ServerLevel level : server.getAllLevels()) {
            if (level != null && !level.noSave) {
                level.noSave = true;
                frozenLevels.add(level);
            }
        }
        frozen = true;
        owner = java.util.Objects.requireNonNull(token);
        MineBackup.LOGGER.info("Auto-save frozen for {} dimensions.", frozenLevels.size());
        return true;
    }

    public synchronized boolean unfreeze() {
        if (!frozen) {
            return false;
        }
        for (ServerLevel level : frozenLevels) {
            if (level != null && level.noSave) {
                level.noSave = false;
            }
        }
        frozenLevels.clear();
        frozen = false;
        owner = null;
        MineBackup.LOGGER.info("Auto-save resumed.");
        return true;
    }

    public synchronized boolean isFrozen() {
        return frozen;
    }

    public synchronized boolean unfreeze(Object token) {
        return token != null && token.equals(owner) && unfreeze();
    }
}
