package com.leafuke.minebackup.runtime;

import com.leafuke.minebackup.MineBackup;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;

public final class AutoSaveController {
    private final List<ServerLevel> frozenLevels = new ArrayList<>();
    private final java.util.Set<ServerLevel> observedLevels = new java.util.HashSet<>();
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
        observedLevels.clear();
        for (ServerLevel level : server.getAllLevels()) {
            observedLevels.add(level);
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
        observedLevels.clear();
        frozen = false;
        owner = null;
        MineBackup.LOGGER.info("Auto-save resumed.");
        return true;
    }

    public synchronized boolean continuous(MinecraftServer server) {
        if (!frozen) return false;
        int count = 0;
        for (ServerLevel level : server.getAllLevels()) {
            if (level == null || !level.noSave || !observedLevels.contains(level)) return false;
            count++;
        }
        return count == observedLevels.size();
    }

    public synchronized boolean isFrozen() {
        return frozen;
    }

    public synchronized boolean unfreeze(Object token) {
        return token != null && token.equals(owner) && unfreeze();
    }
}
