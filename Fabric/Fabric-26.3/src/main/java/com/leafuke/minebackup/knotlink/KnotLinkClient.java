package com.leafuke.minebackup.knotlink;

import com.leafuke.minebackup.MineBackup;
import com.leafuke.minebackup.knotlink.protocol.KnotLinkCodec;
import com.leafuke.minebackup.knotlink.protocol.KnotLinkProtocolException;
import com.leafuke.minebackup.knotlink.protocol.KnotLinkRequest;
import com.leafuke.minebackup.knotlink.protocol.KnotLinkResponse;
import com.leafuke.minebackup.knotlink.sdk.OpenSocketQuerier;
import com.leafuke.minebackup.knotlink.sdk.SignalSubscriber;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * MineBackup lifecycle adapter around KnotLink SDK 2.0.
 */
public final class KnotLinkClient implements AutoCloseable {
    private static final String HOST = "127.0.0.1";
    private static final int QUERY_PORT = 6376;
    private static final int SUBSCRIBER_PORT = 6372;
    private static final String APP_ID = "0x00000020";
    private static final String OPEN_SOCKET_ID = "0x00000010";
    private static final String SIGNAL_ID = "0x00000020";
    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;
    private static final int RESPONSE_TIMEOUT_MILLIS = 5_000;
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    private final ThreadPoolExecutor queryExecutor = new ThreadPoolExecutor(
            4,
            4,
            30L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64),
            daemonThreadFactory("minebackup-knotlink-query-"),
            new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService reconnectExecutor =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                    daemonThreadFactory("minebackup-knotlink-reconnect-"));
    private final Object subscriberLock = new Object();

    private volatile boolean closed;
    private boolean subscriberRunning;
    private int reconnectDelaySeconds = 1;
    private SignalSubscriber subscriber;
    private Consumer<Map<String, String>> signalListener;

    public CompletableFuture<KnotLinkResponse> query(KnotLinkRequest request) {
        Objects.requireNonNull(request, "request");
        if (closed) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("KnotLink client is closed"));
        }

        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return queryBlocking(request);
                } catch (Exception exception) {
                    throw new CompletionException(exception);
                }
            }, queryExecutor);
        } catch (RejectedExecutionException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    public void startSubscriber(Consumer<Map<String, String>> listener) {
        Objects.requireNonNull(listener, "listener");
        synchronized (subscriberLock) {
            signalListener = listener;
            if (closed || subscriberRunning) {
                return;
            }
            subscriberRunning = true;
            reconnectDelaySeconds = 1;
        }
        scheduleSubscriberConnect(0);
    }

    public void stopSubscriber() {
        SignalSubscriber current;
        synchronized (subscriberLock) {
            subscriberRunning = false;
            signalListener = null;
            current = subscriber;
            subscriber = null;
        }
        if (current != null) {
            current.stop();
        }
    }

    private KnotLinkResponse queryBlocking(KnotLinkRequest request) throws Exception {
        try (OpenSocketQuerier querier = new OpenSocketQuerier(
                APP_ID,
                OPEN_SOCKET_ID,
                HOST,
                QUERY_PORT,
                CONNECT_TIMEOUT_MILLIS,
                MAX_RESPONSE_BYTES)) {
            String payload = querier.query(
                    request.serialize(),
                    RESPONSE_TIMEOUT_MILLIS,
                    TimeUnit.MILLISECONDS);
            int responseBytes = payload.getBytes(StandardCharsets.UTF_8).length;
            if (responseBytes > MAX_RESPONSE_BYTES) {
                throw new IOException(
                        "KnotLink response exceeds " + MAX_RESPONSE_BYTES + " bytes");
            }
            return KnotLinkResponse.parse(payload);
        }
    }

    private void scheduleSubscriberConnect(int delaySeconds) {
        if (!shouldRunSubscriber()) {
            return;
        }
        try {
            reconnectExecutor.schedule(
                    this::connectSubscriber,
                    delaySeconds,
                    TimeUnit.SECONDS);
        } catch (RejectedExecutionException exception) {
            if (!closed) {
                MineBackup.LOGGER.warn(
                        "KnotLink subscriber reconnect executor rejected a task",
                        exception);
            }
        }
    }

    private void connectSubscriber() {
        SignalSubscriber candidate = new SignalSubscriber(
                APP_ID,
                SIGNAL_ID,
                HOST,
                SUBSCRIBER_PORT,
                CONNECT_TIMEOUT_MILLIS);
        candidate.setSignalListener(this::dispatchSignal);
        candidate.setDisconnectListener(cause -> onSubscriberDisconnected(candidate, cause));

        synchronized (subscriberLock) {
            if (closed || !subscriberRunning || subscriber != null) {
                return;
            }
            subscriber = candidate;
        }

        try {
            candidate.start();
            boolean active;
            synchronized (subscriberLock) {
                active = subscriber == candidate && subscriberRunning && !closed;
                if (active) {
                    reconnectDelaySeconds = 1;
                }
            }
            if (active) {
                MineBackup.LOGGER.info("Connected to KnotLink SDK 2.0 signal channel.");
            } else {
                candidate.stop();
            }
        } catch (IOException | RuntimeException exception) {
            candidate.stop();
            boolean reconnect = clearSubscriber(candidate);
            if (reconnect) {
                int delay = nextReconnectDelay();
                MineBackup.LOGGER.warn(
                        "Failed to connect to KnotLink signal channel; retrying in {} seconds",
                        delay,
                        exception);
                scheduleSubscriberConnect(delay);
            }
        }
    }

    private void onSubscriberDisconnected(SignalSubscriber disconnected, Throwable cause) {
        disconnected.stop();
        if (!clearSubscriber(disconnected)) {
            return;
        }

        int delay = nextReconnectDelay();
        if (cause == null) {
            MineBackup.LOGGER.warn(
                    "KnotLink signal channel closed; retrying in {} seconds",
                    delay);
        } else {
            MineBackup.LOGGER.warn(
                    "KnotLink signal channel failed; retrying in {} seconds",
                    delay,
                    cause);
        }
        scheduleSubscriberConnect(delay);
    }

    private boolean clearSubscriber(SignalSubscriber expected) {
        synchronized (subscriberLock) {
            if (subscriber != expected) {
                return false;
            }
            subscriber = null;
            return subscriberRunning && !closed;
        }
    }

    private boolean shouldRunSubscriber() {
        synchronized (subscriberLock) {
            return subscriberRunning && !closed;
        }
    }

    private int nextReconnectDelay() {
        synchronized (subscriberLock) {
            int current = reconnectDelaySeconds;
            reconnectDelaySeconds = Math.min(reconnectDelaySeconds * 2, 30);
            return current;
        }
    }

    private void dispatchSignal(String payload) {
        try {
            Map<String, String> fields = KnotLinkCodec.parse(payload);
            if (!fields.containsKey("event")) {
                MineBackup.LOGGER.debug("Ignoring KnotLink signal without event field.");
                return;
            }

            Consumer<Map<String, String>> listener;
            synchronized (subscriberLock) {
                listener = signalListener;
            }
            if (listener != null) {
                listener.accept(fields);
            }
        } catch (KnotLinkProtocolException exception) {
            MineBackup.LOGGER.warn(
                    "Rejected malformed KnotLink v2 signal: {}",
                    exception.getMessage());
        } catch (RuntimeException exception) {
            MineBackup.LOGGER.error(
                    "Unhandled error while dispatching KnotLink signal",
                    exception);
        }
    }

    @Override
    public void close() {
        SignalSubscriber current;
        synchronized (subscriberLock) {
            if (closed) {
                return;
            }
            closed = true;
            subscriberRunning = false;
            signalListener = null;
            current = subscriber;
            subscriber = null;
        }
        if (current != null) {
            current.stop();
        }
        reconnectExecutor.shutdownNow();
        queryExecutor.shutdownNow();
    }

    private static ThreadFactory daemonThreadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, prefix + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
