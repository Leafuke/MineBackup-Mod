/*
 * KnotLink SDK - Java
 * Copyright (c) 2024-2026 KnotLink Contributors
 * SPDX-License-Identifier: MIT
 */

package com.leafuke.minebackup.knotlink.sdk;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * KnotLink signal subscriber. Reconnection policy belongs to the embedding
 * application because the SDK does not assume a lifecycle model.
 */
public final class SignalSubscriber implements AutoCloseable {
    @FunctionalInterface
    public interface SignalListener {
        void onSignalReceived(String data);
    }

    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 6372;
    private static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 5_000;

    private final String appId;
    private final String signalId;
    private final String host;
    private final int port;
    private final int connectTimeoutMillis;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean stopping = new AtomicBoolean();

    private volatile SignalListener signalListener;
    private volatile Consumer<Throwable> disconnectListener;
    private volatile TcpClient tcpClient;

    public SignalSubscriber(String appId, String signalId) {
        this(appId, signalId, DEFAULT_HOST, DEFAULT_PORT, DEFAULT_CONNECT_TIMEOUT_MILLIS);
    }

    public SignalSubscriber(
            String appId,
            String signalId,
            String host,
            int port,
            int connectTimeoutMillis) {
        this.appId = requireId(appId, "appId");
        this.signalId = requireId(signalId, "signalId");
        this.host = Objects.requireNonNull(host, "host");
        if (host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port is out of range: " + port);
        }
        if (connectTimeoutMillis <= 0) {
            throw new IllegalArgumentException("connectTimeoutMillis must be positive");
        }
        this.port = port;
        this.connectTimeoutMillis = connectTimeoutMillis;
    }

    public void setSignalListener(SignalListener listener) {
        signalListener = Objects.requireNonNull(listener, "listener");
    }

    public void setDisconnectListener(Consumer<Throwable> listener) {
        disconnectListener = listener;
    }

    public void start() throws IOException {
        if (signalListener == null) {
            throw new IllegalStateException("Signal listener must be set before starting");
        }
        if (stopping.get()) {
            throw new IOException("KnotLink signal subscriber is stopped");
        }
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("Signal subscriber is already started");
        }

        TcpClient client = new TcpClient(TcpClient.FrameFormat.MAGIC_V2);
        tcpClient = client;
        client.setDataReceivedListener(data -> signalListener.onSignalReceived(data));
        client.setClosedListener(cause -> {
            if (stopping.get()) {
                return;
            }
            Consumer<Throwable> listener = disconnectListener;
            if (listener != null) {
                listener.accept(cause);
            }
        });

        try {
            if (stopping.get()) {
                throw new IOException("KnotLink signal subscriber was stopped before connecting");
            }
            client.connect(host, port, connectTimeoutMillis);
            if (stopping.get()) {
                throw new IOException("KnotLink signal subscriber was stopped while connecting");
            }
            client.sendData(appId + "-" + signalId);
            if (stopping.get()) {
                throw new IOException("KnotLink signal subscriber was stopped while registering");
            }
        } catch (IOException | RuntimeException exception) {
            stopping.set(true);
            client.close();
            throw exception;
        }
    }

    public boolean isRunning() {
        TcpClient client = tcpClient;
        return client != null && client.isRunning();
    }

    public void stop() {
        stopping.set(true);
        TcpClient client = tcpClient;
        tcpClient = null;
        if (client != null) {
            client.close();
        }
    }

    private static String requireId(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    @Override
    public void close() {
        stop();
    }
}
