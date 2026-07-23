/*
 * KnotLink SDK - Java
 * Copyright (c) 2024-2026 KnotLink Contributors
 * SPDX-License-Identifier: MIT
 */

package com.leafuke.minebackup.knotlink.sdk;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * KnotLink OpenSocket query client.
 *
 * <p>One instance supports one outstanding request. Create separate instances
 * when requests must run concurrently.</p>
 */
public final class OpenSocketQuerier implements AutoCloseable {
    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 6376;
    private static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 5_000;
    private static final int DEFAULT_MAX_MESSAGE_BYTES = 16 * 1024 * 1024;

    private final String appId;
    private final String openSocketId;
    private final TcpClient tcpClient;
    private final AtomicReference<CompletableFuture<String>> pending = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public OpenSocketQuerier(String appId, String openSocketId) throws IOException {
        this(appId, openSocketId, DEFAULT_HOST, DEFAULT_PORT, DEFAULT_CONNECT_TIMEOUT_MILLIS);
    }

    public OpenSocketQuerier(
            String appId,
            String openSocketId,
            String host,
            int port,
            int connectTimeoutMillis) throws IOException {
        this(
                appId,
                openSocketId,
                host,
                port,
                connectTimeoutMillis,
                DEFAULT_MAX_MESSAGE_BYTES);
    }

    public OpenSocketQuerier(
            String appId,
            String openSocketId,
            String host,
            int port,
            int connectTimeoutMillis,
            int maxMessageBytes) throws IOException {
        this.appId = requireId(appId, "appId");
        this.openSocketId = requireId(openSocketId, "openSocketId");
        tcpClient = new TcpClient(
                Duration.ofMinutes(3),
                TcpClient.FrameFormat.LENGTH_PREFIXED,
                maxMessageBytes);
        tcpClient.setDataReceivedListener(this::onDataReceived);
        tcpClient.setClosedListener(this::onConnectionClosed);
        tcpClient.connect(host, port, connectTimeoutMillis);
    }

    public CompletableFuture<String> queryAsync(String question) {
        Objects.requireNonNull(question, "question");
        if (closed.get()) {
            return CompletableFuture.failedFuture(
                    new IOException("KnotLink OpenSocket querier is closed"));
        }

        CompletableFuture<String> future = new CompletableFuture<>();
        if (!pending.compareAndSet(null, future)) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("A KnotLink query is already pending"));
        }

        try {
            tcpClient.sendData(appId + "-" + openSocketId + "&*&" + question);
        } catch (IOException exception) {
            pending.compareAndSet(future, null);
            future.completeExceptionally(exception);
        }
        future.whenComplete((response, error) -> pending.compareAndSet(future, null));
        return future;
    }

    public String query(String question, long timeout, TimeUnit unit) throws Exception {
        Objects.requireNonNull(unit, "unit");
        if (timeout <= 0) {
            throw new IllegalArgumentException("timeout must be positive");
        }

        CompletableFuture<String> future = queryAsync(question);
        try {
            return future.get(timeout, unit);
        } catch (TimeoutException exception) {
            if (pending.compareAndSet(future, null)) {
                future.completeExceptionally(exception);
            }
            throw exception;
        }
    }

    private void onDataReceived(String data) {
        CompletableFuture<String> future = pending.getAndSet(null);
        if (future != null) {
            future.complete(data);
        }
    }

    private void onConnectionClosed(Throwable cause) {
        CompletableFuture<String> future = pending.getAndSet(null);
        if (future == null) {
            return;
        }
        IOException failure = new IOException("KnotLink query connection closed before a response");
        if (cause != null) {
            failure.initCause(cause);
        }
        future.completeExceptionally(failure);
    }

    private static String requireId(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture<String> future = pending.getAndSet(null);
        if (future != null) {
            future.completeExceptionally(new IOException("KnotLink query was cancelled"));
        }
        tcpClient.close();
    }
}
