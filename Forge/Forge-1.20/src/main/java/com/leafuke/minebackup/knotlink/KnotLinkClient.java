package com.leafuke.minebackup.knotlink;

import com.leafuke.minebackup.MineBackup;
import com.leafuke.minebackup.api.v2.BackendStatusResult;
import com.leafuke.minebackup.api.v2.OperationFailure;
import java.util.concurrent.TimeoutException;
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

    @FunctionalInterface
    interface Connector { OpenSocketQuerier connect() throws IOException; }
    private final Connector connector;
    private final String host;
    private final int queryPort;
    private final int connectTimeoutMillis;
    private final int responseTimeoutMillis;
    private final java.util.Set<CompletableFuture<KnotLinkResponse>> pendingQueries =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public KnotLinkClient() {
        this(HOST, QUERY_PORT, CONNECT_TIMEOUT_MILLIS, RESPONSE_TIMEOUT_MILLIS);
    }

    // Package-private transport seam for controlled loopback tests; production endpoints stay fixed.
    KnotLinkClient(String host, int queryPort, int connectTimeoutMillis, int responseTimeoutMillis) {
        this(host, queryPort, connectTimeoutMillis, responseTimeoutMillis, null);
    }

    KnotLinkClient(Connector connector) {
        this(HOST, QUERY_PORT, CONNECT_TIMEOUT_MILLIS, RESPONSE_TIMEOUT_MILLIS,
                Objects.requireNonNull(connector, "connector"));
    }

    private KnotLinkClient(String host, int queryPort, int connectTimeoutMillis,
            int responseTimeoutMillis, Connector connector) {
        this.host = Objects.requireNonNull(host, "host");
        this.queryPort = queryPort;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.responseTimeoutMillis = responseTimeoutMillis;
        this.connector = connector != null ? connector : () -> new OpenSocketQuerier(APP_ID, OPEN_SOCKET_ID,
                this.host, this.queryPort, this.connectTimeoutMillis, MAX_RESPONSE_BYTES);
    }

    private final java.util.concurrent.atomic.AtomicLong capabilityEpoch = new java.util.concurrent.atomic.AtomicLong();
    public long capabilityEpoch() { return capabilityEpoch.get(); }

    private volatile boolean closed;
    private boolean subscriberRunning;
    private BackendStatusResult.ChannelState signalState = BackendStatusResult.ChannelState.UNKNOWN;
    private int reconnectDelaySeconds = 1;
    private SignalSubscriber subscriber;
    private Consumer<Map<String, String>> signalListener;

    public CompletableFuture<KnotLinkResponse> query(KnotLinkRequest request) {
        Objects.requireNonNull(request, "request");
        if (closed) {
            return CompletableFuture.failedFuture(
                    new KnotLinkCommunicationException(OperationFailure.Code.CLIENT_CLOSED,
                            "KnotLink client is closed", null));
        }

        try {
            CompletableFuture<KnotLinkResponse> future = CompletableFuture.supplyAsync(() -> {
                try {
                    return queryBlocking(request);
                } catch (Exception exception) {
                    throw new CompletionException(exception);
                }
            }, queryExecutor);
            pendingQueries.add(future);
            future.whenComplete((response, error) -> pendingQueries.remove(future));
            if (closed) future.completeExceptionally(new KnotLinkCommunicationException(
                    OperationFailure.Code.CLIENT_CLOSED, "KnotLink client is closed", null));
            return future;
        } catch (RejectedExecutionException exception) {
            return CompletableFuture.failedFuture(new KnotLinkCommunicationException(
                    closed ? OperationFailure.Code.CLIENT_CLOSED : OperationFailure.Code.QUERY_QUEUE_FULL,
                    "KnotLink query executor rejected a task", exception));
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
            signalState = BackendStatusResult.ChannelState.UNKNOWN;
            reconnectDelaySeconds = 1;
        }
        scheduleSubscriberConnect(0);
    }

    public void stopSubscriber() {
        SignalSubscriber current;
        synchronized (subscriberLock) {
            subscriberRunning = false;
            signalState = BackendStatusResult.ChannelState.UNKNOWN;
            signalListener = null;
            current = subscriber;
            capabilityEpoch.incrementAndGet();
            subscriber = null;
        }
        if (current != null) {
            current.stop();
        }
    }

    public BackendStatusResult.ChannelState signalChannelState() {
        synchronized (subscriberLock) {
            if (closed || !subscriberRunning) return BackendStatusResult.ChannelState.UNKNOWN;
            if (subscriber != null && subscriber.isRunning()) return BackendStatusResult.ChannelState.CONNECTED;
            return signalState == BackendStatusResult.ChannelState.CONNECTED
                    ? BackendStatusResult.ChannelState.UNREACHABLE : signalState;
        }
    }

    private KnotLinkResponse queryBlocking(KnotLinkRequest request) throws Exception {
        if (closed) throw new KnotLinkCommunicationException(OperationFailure.Code.CLIENT_CLOSED,
                "KnotLink client is closed", null);
        OpenSocketQuerier connected;
        try {
            connected = connector.connect();
        } catch (IOException exception) {
            throw new KnotLinkCommunicationException(closed ? OperationFailure.Code.CLIENT_CLOSED
                    : OperationFailure.Code.KNOTLINK_UNREACHABLE,
                    "Cannot connect to the KnotLink query service", exception);
        }
        try (OpenSocketQuerier querier = connected) {
            String payload;
            try {
                payload = querier.query(request.serialize(), responseTimeoutMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException timeout) {
                throw new KnotLinkCommunicationException(OperationFailure.Code.RESPONSE_TIMEOUT,
                        "Backend did not respond before the query deadline", timeout);
            } catch (Exception error) {
                OperationFailure failure = KnotLinkCommunicationException.failure(error);
                throw new KnotLinkCommunicationException(
                        closed ? OperationFailure.Code.CLIENT_CLOSED : failure.code() == OperationFailure.Code.PROTOCOL_ERROR
                                ? OperationFailure.Code.PROTOCOL_ERROR : OperationFailure.Code.CONNECTION_CLOSED,
                        "KnotLink query failed after connecting", error);
            }
            if ("offline".equals(payload)) {
                throw new KnotLinkCommunicationException(OperationFailure.Code.BACKEND_OFFLINE,
                        "KnotLink has no registered backend responder", null);
            }
            try {
                if (payload.getBytes(StandardCharsets.UTF_8).length > MAX_RESPONSE_BYTES) {
                    throw new KnotLinkProtocolException("Oversized KnotLink response");
                }
                return KnotLinkResponse.parse(payload);
            } catch (KnotLinkProtocolException malformed) {
                throw new KnotLinkCommunicationException(OperationFailure.Code.PROTOCOL_ERROR,
                        "Invalid backend protocol response", malformed);
            }
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
                    capabilityEpoch.incrementAndGet();
                    signalState = BackendStatusResult.ChannelState.CONNECTED;
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
            capabilityEpoch.incrementAndGet();
            subscriber = null;
            signalState = subscriberRunning && !closed
                    ? BackendStatusResult.ChannelState.UNREACHABLE : BackendStatusResult.ChannelState.UNKNOWN;
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
            capabilityEpoch.incrementAndGet();
            closed = true;
            subscriberRunning = false;
            signalState = BackendStatusResult.ChannelState.UNKNOWN;
            signalListener = null;
            current = subscriber;
            capabilityEpoch.incrementAndGet();
            subscriber = null;
        }
        if (current != null) {
            current.stop();
        }
        reconnectExecutor.shutdownNow();
        pendingQueries.forEach(future -> future.completeExceptionally(new KnotLinkCommunicationException(
                OperationFailure.Code.CLIENT_CLOSED, "KnotLink client is closed", null)));
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
