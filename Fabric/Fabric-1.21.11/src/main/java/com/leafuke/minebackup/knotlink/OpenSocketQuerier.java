package com.leafuke.minebackup.knotlink;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.InputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

public class OpenSocketQuerier {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String SERVER_IP = "127.0.0.1";
    private static final int QUERIER_PORT = 6376;

    public static CompletableFuture<String> query(String appID, String openSocketID, String question) {
        return CompletableFuture.supplyAsync(() -> {
            try (Socket socket = new Socket(SERVER_IP, QUERIER_PORT);
                 PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                 InputStream in = socket.getInputStream()) {

                socket.setSoTimeout(5000); // 5秒超时

                String packet = String.format("%s-%s&*&%s", appID, openSocketID, question);
                LOGGER.info("Sending query to KnotLink: {}", question);
                // C++ 服务端没有按行读取，所以我们用 print 而不是 println，并且手动 flush
                out.print(packet);
                out.flush();

                // 关键修正：不使用 readLine()，而是直接读取字节流
                byte[] buffer = new byte[4096]; // 分配一个足够大的缓冲区
                int bytesRead = in.read(buffer);

                if (bytesRead > 0) {
                    String response = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                    LOGGER.info("Received query response: {}", response);
                    return response;
                } else {
                    LOGGER.warn("Received no response from KnotLink server.");
                    return "ERROR:NO_RESPONSE";
                }

            } catch (Exception e) {
                LOGGER.error("Failed to query KnotLink server: {}", e.getMessage());
                return "ERROR:COMMUNICATION_FAILED";
            }
        });
    }
}