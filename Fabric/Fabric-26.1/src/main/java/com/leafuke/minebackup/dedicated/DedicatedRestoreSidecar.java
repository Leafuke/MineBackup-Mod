package com.leafuke.minebackup.dedicated;

/** Stable child-process entry point, also used by the physical classpath preflight. */
public final class DedicatedRestoreSidecar {
    private DedicatedRestoreSidecar() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("MineBackup sidecar requires the restart session directory");
            System.exit(2);
        }
        InteropRestoreSidecar.main(args);
    }
}
