package com.Leafuke.minebackup;

// 移除旧版日志导入
// import com.mojang.logging.LogUtils;
// import org.slf4j.Logger;

// 添加 Log4j 日志支持
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class SignalSubscriber {
    // 修改日志初始化方式
    private static final Logger LOGGER = LogManager.getLogger();
    private TcpClient KLsubscriber;
    private final String appID;
    private final String signalID;

    public interface SignalListener {
        void onSignalReceived(String data);
    }

    private SignalListener signalListener;

    public SignalSubscriber(String appID, String signalID) {
        this.appID = appID;
        this.signalID = signalID;
    }

    public void setSignalListener(SignalListener listener) {
        this.signalListener = listener;
    }

    public void start() {
        KLsubscriber = new TcpClient();
        if (KLsubscriber.connectToServer("127.0.0.1", 6372)) {
            KLsubscriber.setDataReceivedListener(data -> {
                // 1.16.5 中 System.out 仍可正常使用
                System.out.println("DEBUG: Raw data received from KnotLink: " + data);
                if (signalListener != null) {
                    signalListener.onSignalReceived(data);
                }
            });

            String s_key = appID + "-" + signalID;
            KLsubscriber.sendData(s_key);
            LOGGER.info("SignalSubscriber started and subscribed to {}.", s_key);
        } else {
            LOGGER.error("SignalSubscriber failed to start.");
        }
    }

    public void stop() {
        if (KLsubscriber != null) {
            KLsubscriber.close();
            LOGGER.info("SignalSubscriber stopped.");
        }
    }
}