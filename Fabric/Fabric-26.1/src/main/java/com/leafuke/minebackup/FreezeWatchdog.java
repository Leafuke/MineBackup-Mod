package com.leafuke.minebackup;

public final class FreezeWatchdog {
    public static final long TIMEOUT_MS = 3L * 60L * 1000L;

    private FreezeWatchdog() {
    }

    public static long elapsedSince(long freezeTimestamp) {
        if (freezeTimestamp <= 0L) {
            return 0L;
        }
        return System.currentTimeMillis() - freezeTimestamp;
    }

    public static boolean hasTimedOut(long freezeTimestamp) {
        return freezeTimestamp > 0L && elapsedSince(freezeTimestamp) > TIMEOUT_MS;
    }
}
