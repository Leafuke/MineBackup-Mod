package com.leafuke.minebackup.knotlink;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.net.Socket;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TcpClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    private Socket socket;
    private PrintWriter out;
    private InputStream in;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final String heartbeatMessage = "heartbeat";
    private final String heartbeatResponse = "heartbeat_response";
    private boolean running = false;

    public TcpClient() {
    }

    public boolean connectToServer(String host, int port) {
        try {
            this.socket = new Socket(host, port);
            this.out = new PrintWriter(new OutputStreamWriter(this.socket.getOutputStream(), StandardCharsets.UTF_8), true);
            this.in = this.socket.getInputStream();
            LOGGER.info("Connected to KnotLink server at {}:{}", host, port);
            Thread reader = new Thread(this::readData, "minebackup-knotlink-reader");
            reader.setDaemon(true);
            reader.start();
            this.startHeartbeat();
            return true;
        } catch (IOException e) {
            LOGGER.error("Failed to connect to KnotLink server: {}", e.getMessage());
            return false;
        }
    }

    public void sendData(String data) {
        if (this.out != null) {
            this.out.print(data); // 使用 print 方法，不会添加换行符
            this.out.flush(); // 确保数据立即发送
        } else {
            LOGGER.warn("Socket is not connected.");
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
        LOGGER.debug("Start reading data from KnotLink server...");
        try {
            byte[] buffer = new byte[1024];
            while (true) {
                int bytesRead = in.read(buffer); // 读取数据
                if (bytesRead == -1) {
                    break; // 如果没有数据可读，退出循环
                }
                String receivedData = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                LOGGER.debug("Received raw data: {}", receivedData);
                if (receivedData.trim().equals(heartbeatResponse)) {
                    continue; // 如果是心跳响应，跳过处理
                }
                // Handle received data
                if (dataReceivedListener != null) {
                    dataReceivedListener.onDataReceived(receivedData);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("KnotLink socket error: {}", e.getMessage());
        } finally {
            stopHeartbeat();
            LOGGER.info("KnotLink server disconnected.");
        }
    }

    public interface DataReceivedListener {
        void onDataReceived(String data);
    }

    private DataReceivedListener dataReceivedListener;

    public void setDataReceivedListener(DataReceivedListener listener) {
        this.dataReceivedListener = listener;
        LOGGER.debug("DataReceivedListener set successfully.");
    }

    public void close() {
        this.stopHeartbeat();
        try {
            if (this.socket != null) {
                this.socket.close();
            }
        } catch (IOException e) {
            LOGGER.warn("Error closing socket: {}", e.getMessage());
        }
    }
}