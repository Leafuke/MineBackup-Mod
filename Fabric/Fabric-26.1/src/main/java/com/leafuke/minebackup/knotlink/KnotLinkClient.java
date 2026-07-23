package com.leafuke.minebackup.knotlink;

import com.leafuke.minebackup.MineBackup;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class KnotLinkClient implements AutoCloseable {
    private static final String HOST = "127.0.0.1";
    private static final int QUERY_PORT = 6376;
    private static final int SUBSCRIBER_PORT = 6372;
    private static final String APP_ID = "0x00000020";
    private static final String OPEN_SOCKET_ID = "0x00000010";
    private static final String SIGNAL_ID = "0x00000020";
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 5_000;
    private static final int DRAIN_TIMEOUT_MS = 50;
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final String HEARTBEAT = "heartbeat";
    private static final String HEARTBEAT_RESPONSE = "heartbeat_response";

    private final ThreadPoolExecutor ioExecutor = new ThreadPoolExecutor(
            4,
            4,
            30L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64),
            daemonThreadFactory("minebackup-knotlink-io-"),
            new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
            daemonThreadFactory("minebackup-knotlink-scheduler-"));
    private final Object subscriberLock = new Object();

    private volatile boolean closed;
    private volatile boolean subscriberRunning;
    private volatile Socket subscriberSocket;
    private volatile Consumer<Map<String, String>> signalListener;
    private int reconnectDelaySeconds = 1;

    public CompletableFuture<KnotLinkResponse> query(KnotLinkRequest request) {
        Objects.requireNonNull(request, "request");
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("KnotLink client is closed"));
        }

        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return queryBlocking(request);
                } catch (IOException | KnotLinkProtocolException exception) {
                    throw new java.util.concurrent.CompletionException(exception);
                }
            }, ioExecutor);
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
            scheduleSubscriberConnect(0);
        }
    }

    public void stopSubscriber() {
        synchronized (subscriberLock) {
            subscriberRunning = false;
            signalListener = null;
            closeSubscriberSocket();
        }
    }

    private KnotLinkResponse queryBlocking(KnotLinkRequest request)
            throws IOException, KnotLinkProtocolException {
        try (Socket socket = connect(QUERY_PORT)) {
            String packet = APP_ID + "-" + OPEN_SOCKET_ID + "&*&" + request.serialize();
            OutputStream output = socket.getOutputStream();
            output.write(packet.getBytes(StandardCharsets.UTF_8));
            output.flush();

            String payload = readMessage(socket, MAX_RESPONSE_BYTES);
            return KnotLinkResponse.parse(payload);
        }
    }

    private void scheduleSubscriberConnect(int delaySeconds) {
        if (!subscriberRunning || closed) {
            return;
        }
        scheduler.schedule(() -> {
            if (!subscriberRunning || closed) {
                return;
            }
            try {
                ioExecutor.execute(this::runSubscriberConnection);
            } catch (RejectedExecutionException exception) {
                MineBackup.LOGGER.warn("KnotLink subscriber reconnect queue is full", exception);
                scheduleSubscriberConnect(nextReconnectDelay());
            }
        }, delaySeconds, TimeUnit.SECONDS);
    }

    private void runSubscriberConnection() {
        ScheduledFuture<?> heartbeat = null;
        try (Socket socket = connect(SUBSCRIBER_PORT)) {
            synchronized (subscriberLock) {
                if (!subscriberRunning || closed) {
                    return;
                }
                subscriberSocket = socket;
                reconnectDelaySeconds = 1;
            }

            OutputStream output = socket.getOutputStream();
            synchronized (output) {
                output.write((APP_ID + "-" + SIGNAL_ID).getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
            socket.setSoTimeout(0);
            MineBackup.LOGGER.info("Connected to KnotLink signal channel.");

            heartbeat = scheduler.scheduleAtFixedRate(
                    () -> sendHeartbeat(socket, output),
                    3L,
                    3L,
                    TimeUnit.MINUTES);

            InputStream input = socket.getInputStream();
            byte[] buffer = new byte[8192];
            while (subscriberRunning && !closed) {
                int bytesRead = input.read(buffer);
                if (bytesRead < 0) {
                    break;
                }
                String payload = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                if (HEARTBEAT_RESPONSE.equals(payload.trim())) {
                    continue;
                }
                dispatchSignal(payload);
            }
        } catch (IOException exception) {
            if (subscriberRunning && !closed) {
                MineBackup.LOGGER.warn("KnotLink signal connection lost", exception);
            }
        } finally {
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
            synchronized (subscriberLock) {
                subscriberSocket = null;
            }
            if (subscriberRunning && !closed) {
                scheduleSubscriberConnect(nextReconnectDelay());
            }
        }
    }

    private void dispatchSignal(String payload) {
        try {
            Map<String, String> fields = KnotLinkCodec.parse(payload);
            if (!fields.containsKey("event")) {
                MineBackup.LOGGER.debug("Ignoring KnotLink signal without event field.");
                return;
            }
            Consumer<Map<String, String>> listener = signalListener;
            if (listener != null) {
                listener.accept(fields);
            }
        } catch (KnotLinkProtocolException exception) {
            MineBackup.LOGGER.warn("Rejected malformed KnotLink v2 signal: {}", exception.getMessage());
        } catch (RuntimeException exception) {
            MineBackup.LOGGER.error("Unhandled error while dispatching KnotLink signal", exception);
        }
    }

    private void sendHeartbeat(Socket socket, OutputStream output) {
        if (!subscriberRunning || closed || socket.isClosed()) {
            return;
        }
        try {
            synchronized (output) {
                output.write(HEARTBEAT.getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
        } catch (IOException exception) {
            MineBackup.LOGGER.debug("Failed to send KnotLink heartbeat", exception);
            try {
                socket.close();
            } catch (IOException closeException) {
                MineBackup.LOGGER.debug("Failed to close broken KnotLink socket", closeException);
            }
        }
    }

    private Socket connect(int port) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(HOST, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            return socket;
        } catch (IOException exception) {
            try {
                socket.close();
            } catch (IOException closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
    }

    private static String readMessage(Socket socket, int maxBytes) throws IOException {
        InputStream input = socket.getInputStream();
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];

        int bytesRead = input.read(buffer);
        if (bytesRead < 0) {
            throw new IOException("KnotLink returned no response");
        }
        appendBounded(response, buffer, bytesRead, maxBytes);

        socket.setSoTimeout(DRAIN_TIMEOUT_MS);
        while (true) {
            try {
                bytesRead = input.read(buffer);
                if (bytesRead < 0) {
                    break;
                }
                appendBounded(response, buffer, bytesRead, maxBytes);
            } catch (SocketTimeoutException expectedEndOfMessage) {
                break;
            }
        }
        return response.toString(StandardCharsets.UTF_8);
    }

    private static void appendBounded(
            ByteArrayOutputStream output,
            byte[] bytes,
            int count,
            int maxBytes) throws IOException {
        if (output.size() + count > maxBytes) {
            throw new IOException("KnotLink response exceeds " + maxBytes + " bytes");
        }
        output.write(bytes, 0, count);
    }

    private int nextReconnectDelay() {
        synchronized (subscriberLock) {
            int current = reconnectDelaySeconds;
            reconnectDelaySeconds = Math.min(reconnectDelaySeconds * 2, 30);
            return current;
        }
    }

    private void closeSubscriberSocket() {
        Socket socket = subscriberSocket;
        subscriberSocket = null;
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException exception) {
            MineBackup.LOGGER.debug("Failed to close KnotLink subscriber socket", exception);
        }
    }

    @Override
    public void close() {
        synchronized (subscriberLock) {
            if (closed) {
                return;
            }
            closed = true;
            subscriberRunning = false;
            signalListener = null;
            closeSubscriberSocket();
        }
        scheduler.shutdownNow();
        ioExecutor.shutdownNow();
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
