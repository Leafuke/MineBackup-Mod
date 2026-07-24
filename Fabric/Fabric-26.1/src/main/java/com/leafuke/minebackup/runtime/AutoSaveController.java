package com.leafuke.minebackup.runtime;

import com.leafuke.minebackup.MineBackup;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class AutoSaveController {
    private static final long FREEZE_TIMEOUT_NANOS = Duration.ofMinutes(3).toNanos();

    private final List<ServerLevel> frozenLevels = new ArrayList<>();
    private final Runnable timeoutListener;
    private boolean frozen;
    private long freezeDeadlineNanos;

    public AutoSaveController() {
        this(() -> {
        });
    }

    public AutoSaveController(Runnable timeoutListener) {
        this.timeoutListener = java.util.Objects.requireNonNull(timeoutListener, "timeoutListener");
    }

    public synchronized boolean freeze(MinecraftServer server) {
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
        freezeDeadlineNanos = System.nanoTime() + FREEZE_TIMEOUT_NANOS;
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
        freezeDeadlineNanos = 0L;
        MineBackup.LOGGER.info("Auto-save resumed.");
        return true;
    }

    public synchronized boolean isFrozen() {
        return frozen;
    }

    public void tick(MinecraftServer server) {
        boolean timedOut;
        synchronized (this) {
            timedOut = frozen && System.nanoTime() >= freezeDeadlineNanos;
        }
        if (!timedOut) {
            return;
        }

        MineBackup.LOGGER.error("Auto-save freeze timed out; forcing resume.");
        if (unfreeze()) {
            timeoutListener.run();
            server.getPlayerList().broadcastSystemMessage(
                    Component.translatable("minebackup.broadcast.autosave.timeout"),
                    false);
        }
    }
}
