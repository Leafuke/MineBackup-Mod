package com.leafuke.minebackup.restore;

/**
 * 热还原状态管理类
 * 用于在热还原过程中跟踪各种状态标志
 */
public final class HotRestoreState {
    private HotRestoreState() {}

    /**
     * 标记是否正在等待服务器停止确认
     * 当开始热还原流程时设置为 true
     */
    public static volatile boolean waitingForServerStopAck = false;

    /**
     * 要重新加入的世界存档文件夹名
     * 在热还原前设置，还原完成后用于自动重连
     */
    public static volatile String levelIdToRejoin = null;

    /**
     * 标记是否正在进行还原操作
     * 防止重复触发还原流程
     */
    public static volatile boolean isRestoring = false;

    // ========== KnotLink 握手信息 ==========

    /** 握手是否已完成 */
    public static volatile boolean handshakeCompleted = false;
    /** 主程序版本号（握手时获取） */
    public static volatile String mainProgramVersion = null;
    /** 版本兼容性标记（模组版本是否满足主程序的最低要求） */
    public static volatile boolean versionCompatible = true;
    /** 主程序要求的最低模组版本 */
    public static volatile String requiredMinModVersion = null;

    /**
     * 重置所有还原相关状态
     * 在还原完成或取消时调用
     */
    public static void reset() {
        waitingForServerStopAck = false;
        levelIdToRejoin = null;
        isRestoring = false;
    }

    /**
     * 重置握手状态
     */
    public static void resetHandshake() {
        handshakeCompleted = false;
        mainProgramVersion = null;
        versionCompatible = true;
        requiredMinModVersion = null;
    }
}
