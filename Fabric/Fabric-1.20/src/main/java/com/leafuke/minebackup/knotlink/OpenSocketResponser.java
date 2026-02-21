package com.leafuke.minebackup.knotlink;

import java.nio.charset.StandardCharsets; // 导入 StandardCharsets

public class OpenSocketResponser {
    private TcpClient KLresponser;
    private String appID;
    private String openSocketID;

    // 定义一个回调接口
    public interface DataListener {
        void onDataReceived(String data, String key);
//        void onDataReceived(byte[] data, String key);
    }

    private DataListener dataListener;

    public OpenSocketResponser(String appID, String openSocketID) {
        this.appID = appID;
        this.openSocketID = openSocketID;
        init();
    }

    public void setDataListener(DataListener listener) {
        this.dataListener = listener;
    }

    private void init() {
        KLresponser = new TcpClient();
        KLresponser.connectToServer("127.0.0.1", 6372);
        System.out.println("OKK");

        // 设置数据接收监听器
        KLresponser.setDataReceivedListener(data -> {
            dataRecv(data.getBytes());
        });

        String s_key = appID + "-" + openSocketID;
        KLresponser.sendData(s_key); // 发送初始化数据
    }

    private void dataRecv(byte[] data) {
        String s_data = new String(data, StandardCharsets.UTF_8);

        String delimiter = "&\\*&"; // 分隔符
        String[] parts = s_data.split(delimiter);

        // 打印每个部分
        for (int i = 0; i < parts.length; i++) {
            System.out.println("Part " + (i + 1) + ": " + parts[i]);
        }

        if (parts.length != 2) {
            System.err.println("Invalid data format. Expected two parts separated by " + delimiter);
            return;
        }

        String key = parts[0]; // 前一部分作为 key
        String t_data = parts[1]; // 后一部分作为 t_data

        // 调用外部回调
        if (dataListener != null) {
            dataListener.onDataReceived(t_data, key);
//            dataListener.onDataReceived(t_data.getBytes(), key);
        }
    }

    public void sendBack(String data, String questionID) {
        sendBack(data.getBytes(), questionID);
    }

    public void sendBack(byte[] data, String questionID) {
        String data_r = questionID + "&*&" + new String(data);
        KLresponser.sendData(data_r);
    }

    public void close() {
        KLresponser.close();
    }
}