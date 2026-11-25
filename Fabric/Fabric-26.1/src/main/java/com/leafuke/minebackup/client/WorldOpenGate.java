package com.leafuke.minebackup.client;

import com.leafuke.minebackup.MineBackup;
import com.leafuke.minebackup.interop.WorldAdmission;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.*;

/** Reserve on the shared target gate before calling Minecraft's level-opening flow. */
public final class WorldOpenGate {
    private static final ThreadLocal<Boolean> INSIDE = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> AUTOMATIC = new ThreadLocal<>();
    private static final ExecutorService io =
            Executors.newSingleThreadExecutor(
                    Thread.ofPlatform().daemon().name("minebackup-world-open").factory());

    private record Opening(Path root, UUID fence, WorldAdmission.Reservation reservation) {}

    private static volatile Opening opening;
    private static boolean pending;

    private WorldOpenGate() {}

    static void automatic(Runnable action) {
        AUTOMATIC.set(true);
        try {
            action.run();
        } finally {
            AUTOMATIC.remove();
        }
    }

    public static boolean intercept(WorldOpenFlows flows, String levelId, Runnable onCancel) {
        if (Boolean.TRUE.equals(INSIDE.get())) return false;
        Minecraft mc = Minecraft.getInstance();
        if (pending || opening != null) return true;
        Path base = mc.getLevelSource().getBaseDir().toAbsolutePath().normalize();
        Path root = base.resolve(levelId).normalize();
        if (!base.equals(root.getParent())) {
            onCancel.run();
            return true;
        }
        var permit =
                Boolean.TRUE.equals(AUTOMATIC.get()) ? MineBackup.openingPermission(root) : null;
        if (Boolean.TRUE.equals(AUTOMATIC.get()) && permit == null) {
            onCancel.run();
            return true;
        }
        pending = true;
        var previous = mc.screen;
        CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                UUID fence =
                                        permit == null
                                                ? MineBackup.knotLink()
                                                        .client()
                                                        .checkManualOpen(root)
                                                : permit.operation();
                                return new Opening(
                                        root,
                                        fence,
                                        WorldAdmission.enter(
                                                root,
                                                permit == null ? null : permit.operation(),
                                                permit == null ? null : permit.domain()));
                            } catch (Exception e) {
                                throw new CompletionException(e);
                            }
                        },
                        io)
                .whenComplete(
                        (gate, error) ->
                                mc.execute(
                                        () -> {
                                            pending = false;
                                            if (error != null
                                                    || mc.screen != previous
                                                    || mc.level != null
                                                    || permit != null
                                                            && (System.nanoTime()
                                                                            >= permit.startBefore()
                                                                    || !permit.equals(
                                                                            MineBackup
                                                                                    .openingPermission(
                                                                                            root)))) {
                                                if (gate != null) release(gate, false);
                                                onCancel.run();
                                                if (error != null)
                                                    mc.setScreen(
                                                            new LinkStatusScreen(
                                                                    previous, "world_in_use"));
                                                return;
                                            }
                                            opening = gate;
                                            INSIDE.set(true);
                                            try {
                                                flows.openWorld(
                                                        levelId,
                                                        () -> {
                                                            abandon(gate);
                                                            onCancel.run();
                                                        });
                                            } catch (RuntimeException ex) {
                                                abandon(gate);
                                                throw ex;
                                            } finally {
                                                INSIDE.remove();
                                            }
                                        }));
        return true;
    }

    public static void opened(Path root) {
        Opening o = opening;
        if (o != null && o.root().equals(root.toAbsolutePath().normalize())) {
            opening = null;
            release(o, true);
        }
    }

    private static void abandon(Opening o) {
        if (opening == o) {
            opening = null;
            release(o, false);
        }
    }

    private static void release(Opening opening, boolean opened) {
        io.execute(
                () -> {
                    try {
                        if (opened)
                            MineBackup.knotLink().client().opened(opening.root(), opening.fence());
                    } catch (Exception e) {
                        MineBackup.LOGGER.warn("World fence cleanup needs attention");
                    } finally {
                        try {
                            opening.reservation().close();
                        } catch (Exception e) {
                            MineBackup.LOGGER.warn("World admission cleanup needs attention");
                        }
                    }
                });
    }
}
