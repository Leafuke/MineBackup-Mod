package com.example.minebackup.knotlink;

import java.io.IOException;

public class SignalSender {
    private TcpClient KLsender;
    private String appID;
    private String signalID;

    public SignalSender(String APPID, String SignalID) {
        this.appID = APPID;
        this.signalID = SignalID;
        init();
    }

    public void setConfig(String APPID, String SignalID) {
        this.appID = APPID;
        this.signalID = SignalID;
    }

    private void init() {
        KLsender = new TcpClient();
        KLsender.connectToServer("127.0.0.1", 6378);
    }

    public void emitt(String data) {
        if (appID == null || signalID == null) {
            System.err.println("APPID and SignalID must be defined.");
            return;
        }
        String s_key = appID + "-" + signalID + "&*&";
        String s_data = s_key + data;
        KLsender.sendData(s_data);
    }
}