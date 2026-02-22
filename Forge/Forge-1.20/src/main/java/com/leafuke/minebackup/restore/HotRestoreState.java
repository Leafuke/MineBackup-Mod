package com.leafuke.minebackup.restore;

public final class HotRestoreState {
    private HotRestoreState() {}

    public static volatile boolean waitingForServerStopAck = false;
    public static volatile String levelIdToRejoin = null;
    public static volatile boolean isRestoring = false;

    public static volatile boolean handshakeCompleted = false;
    public static volatile String mainProgramVersion = null;
    public static volatile boolean versionCompatible = true;
    public static volatile String requiredMinModVersion = null;

    public static void reset() {
        waitingForServerStopAck = false;
        levelIdToRejoin = null;
        isRestoring = false;
    }

    public static void resetHandshake() {
        handshakeCompleted = false;
        mainProgramVersion = null;
        versionCompatible = true;
        requiredMinModVersion = null;
    }
}
