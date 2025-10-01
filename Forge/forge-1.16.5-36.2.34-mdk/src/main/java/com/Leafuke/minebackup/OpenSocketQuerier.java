package com.Leafuke.minebackup;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Simple helper that opens a TCP socket to the external MineBackup helper application,
 * sends a single-line command and waits for a response. Designed for use from the
 * mod's command handlers. Adjust HOST/PORT to match your helper.
 *
 * This is intentionally small and dependency-free so it compiles in MC 1.16.5.
 */
public class OpenSocketQuerier {
    // Default host/port - change to match your MineBackup helper app.
    public static String HOST = "127.0.0.1";
    public static int PORT = 6000;
    public static int CONNECT_TIMEOUT_MS = 2_000;
    public static int READ_TIMEOUT_MS = 10_000;

    public static CompletableFuture<String> query(String appId, String socketId, String command) {
        return CompletableFuture.supplyAsync(() -> {
            Socket socket = null;
            try {
                socket = new Socket();
                socket.connect(new InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MS);
                socket.setSoTimeout(READ_TIMEOUT_MS);

                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));

                // Send header (optional) + command. The receiver must understand this format.
                // We keep the format very simple: APPID|SOCKETID|COMMAND\n
                String payload = String.format("%s|%s|%s", appId, socketId, command);
                out.println(payload);

                // Read single-line response (the helper should reply with a single line)
                String response = in.readLine();
                return response;
            } catch (IOException e) {
                return "ERROR:IO:" + e.getMessage();
            } finally {
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        });
    }
}