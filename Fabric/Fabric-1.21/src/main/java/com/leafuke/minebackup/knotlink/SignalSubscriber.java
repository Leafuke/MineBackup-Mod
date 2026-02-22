package com.leafuke.minebackup.knotlink;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

public class SignalSubscriber {
    private static final Logger LOGGER = LogUtils.getLogger();
    private TcpClient knotLinkSubscriber;
    private final String appID;
    private final String signalID;

    // 定义一个回调接口
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
        knotLinkSubscriber = new TcpClient();
        // SignalSubscriber 连接到端口 6372
        if (knotLinkSubscriber.connectToServer("127.0.0.1", 6372)) {
            // 设置数据接收监听器
            knotLinkSubscriber.setDataReceivedListener(data -> {
                LOGGER.debug("收到 KnotLink 广播数据: {}", data);
                if (signalListener != null) {
                    signalListener.onSignalReceived(data);
                }
            });

            // 发送订阅请求
            String s_key = appID + "-" + signalID;
            knotLinkSubscriber.sendData(s_key);
            LOGGER.info("SignalSubscriber started and subscribed to {}.", s_key);
        } else {
            LOGGER.error("SignalSubscriber failed to start.");
        }
    }

    public void stop() {
        if (knotLinkSubscriber != null) {
            knotLinkSubscriber.close();
            LOGGER.info("SignalSubscriber stopped.");
        }
    }
}