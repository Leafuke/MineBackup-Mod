package com.Leafuke.minebackup.knotlink;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TcpClient {
    private Socket socket;
    private PrintWriter out;
    private InputStream in;
    private ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private String heartbeatMessage = "heartbeat";
    private String heartbeatResponse = "heartbeat_response";
    private boolean running = false;

    public TcpClient() {
    }

    public boolean connectToServer(String host, int port) {
        try {
            this.socket = new Socket(host, port);
            this.out = new PrintWriter(this.socket.getOutputStream(), true);
            this.in = this.socket.getInputStream();
            System.out.println("Connected to server at " + host + ":" + port);
            (new Thread(this::readData)).start();
            this.startHeartbeat();
            return true;
        } catch (IOException e) {
            System.err.println("Failed to connect to server: " + e.getMessage());
            return false;
        }
    }

    public void sendData(String data) {
        if (this.out != null) {
            this.out.print(data); // 使用 print 方法，不会添加换行符
            this.out.flush(); // 确保数据立即发送
        } else {
            System.err.println("Socket is not connected.");
        }
    }

    private void startHeartbeat() {
        this.running = true;
        this.scheduler.scheduleAtFixedRate(() -> {
            if (this.running) {
                this.sendData(this.heartbeatMessage);
            }
        }, 1L, 3L, TimeUnit.MINUTES);
    }

    private void stopHeartbeat() {
        this.running = false;
        this.scheduler.shutdown();
    }

    private void readData() {
        System.out.println("DEBUG: Start reading from server...");
        try {
            byte[] buffer = new byte[1024];
            while (true) {
                int bytesRead = in.read(buffer); // 读取数据
                if (bytesRead == -1) {
                    break; // 如果没有数据可读，退出循环
                }
                String receivedData = new String(buffer, 0, bytesRead);
                System.out.println("Received raw data: " + receivedData);
                if (receivedData.trim().equals(heartbeatResponse)) {
                    continue; // 如果是心跳响应，跳过处理
                }
                // Handle received data
                if (dataReceivedListener != null) {
                    dataReceivedListener.onDataReceived(receivedData);
                }
            }
        } catch (IOException e) {
            System.err.println("Socket error: " + e.getMessage());
        } finally {
            stopHeartbeat();
            System.out.println("Server disconnected.");
        }
    }

    public interface DataReceivedListener {
        void onDataReceived(String data);
    }

    private DataReceivedListener dataReceivedListener;

    public void setDataReceivedListener(DataReceivedListener listener) {
        this.dataReceivedListener = listener;
        System.out.println("DataReceivedListener set successfully.");
    }

    public void close() {
        this.stopHeartbeat();
        try {
            if (this.socket != null) {
                this.socket.close();
            }
        } catch (IOException e) {
            System.err.println("Error closing socket: " + e.getMessage());
        }
    }
}