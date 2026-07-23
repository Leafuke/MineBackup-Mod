/*
 * KnotLink SDK - Java
 * Copyright (c) 2024-2026 KnotLink Contributors
 * SPDX-License-Identifier: MIT
 */

package com.leafuke.minebackup.knotlink.sdk;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * KnotLink SDK 2.0 framed TCP client.
 *
 * <p>KnotLinkService 2.0 currently uses a four-byte big-endian length prefix.
 * The optional magic header remains available for SDK compatibility but is not
 * enabled by MineBackup.</p>
 */
public final class TcpClient implements AutoCloseable {
    private static final System.Logger LOGGER =
            System.getLogger(TcpClient.class.getName());

    public enum FrameFormat {
        LENGTH_PREFIXED,
        MAGIC_V2
    }

    @FunctionalInterface
    public interface DataReceivedListener {
        void onDataReceived(String data);
    }

    private static final byte[] MAGIC = {0x4B, 0x4B, 0x00, 0x02};
    private static final byte[] HEARTBEAT = "heartbeat".getBytes(StandardCharsets.UTF_8);
    private static final byte[] HEARTBEAT_RESPONSE =
            "heartbeat_response".getBytes(StandardCharsets.UTF_8);
    private static final int LENGTH_BYTES = Integer.BYTES;
    private static final int DEFAULT_MAX_MESSAGE_BYTES = 16 * 1024 * 1024;
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();

    private final FrameFormat frameFormat;
    private final Duration heartbeatInterval;
    private final int maxMessageBytes;
    private final Object writeLock = new Object();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean closeNotified = new AtomicBoolean();

    private volatile Socket socket;
    private volatile InputStream input;
    private volatile OutputStream output;
    private volatile Thread readThread;
    private volatile ScheduledExecutorService heartbeatExecutor;
    private volatile DataReceivedListener dataReceivedListener;
    private volatile Consumer<Throwable> errorListener;
    private volatile Consumer<Throwable> closedListener;

    public TcpClient() {
        this(Duration.ofMinutes(3), FrameFormat.LENGTH_PREFIXED, DEFAULT_MAX_MESSAGE_BYTES);
    }

    public TcpClient(Duration heartbeatInterval, FrameFormat frameFormat) {
        this(heartbeatInterval, frameFormat, DEFAULT_MAX_MESSAGE_BYTES);
    }

    public TcpClient(Duration heartbeatInterval, FrameFormat frameFormat, int maxMessageBytes) {
        this.heartbeatInterval = Objects.requireNonNull(heartbeatInterval, "heartbeatInterval");
        this.frameFormat = Objects.requireNonNull(frameFormat, "frameFormat");
        if (heartbeatInterval.isZero() || heartbeatInterval.isNegative()) {
            throw new IllegalArgumentException("Heartbeat interval must be positive");
        }
        if (maxMessageBytes <= 0 || maxMessageBytes > DEFAULT_MAX_MESSAGE_BYTES) {
            throw new IllegalArgumentException(
                    "maxMessageBytes must be between 1 and " + DEFAULT_MAX_MESSAGE_BYTES);
        }
        this.maxMessageBytes = maxMessageBytes;
    }

    public void connect(String host, int port, int timeoutMillis) throws IOException {
        Objects.requireNonNull(host, "host");
        if (host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port is out of range: " + port);
        }
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("timeoutMillis must be positive");
        }

        Socket newSocket;
        synchronized (this) {
            if (closed.get()) {
                throw new IOException("KnotLink TCP client is closed");
            }
            if (running.get() || socket != null) {
                throw new IOException("KnotLink TCP client is already connected");
            }
            newSocket = new Socket();
            // Publish the connecting socket so close() can abort a blocked connect.
            socket = newSocket;
        }

        try {
            newSocket.connect(new InetSocketAddress(host, port), timeoutMillis);
            newSocket.setTcpNoDelay(true);
            InputStream newInput = newSocket.getInputStream();
            OutputStream newOutput = newSocket.getOutputStream();

            synchronized (this) {
                if (closed.get() || socket != newSocket) {
                    throw new IOException("KnotLink TCP client was closed while connecting");
                }
                input = newInput;
                output = newOutput;
                running.set(true);
                startHeartbeat();
                Thread reader = new Thread(
                        this::readLoop,
                        "knotlink-sdk-reader-" + THREAD_COUNTER.incrementAndGet());
                reader.setDaemon(true);
                readThread = reader;
                reader.start();
            }
        } catch (IOException | RuntimeException exception) {
            stopHeartbeat();
            synchronized (this) {
                if (socket == newSocket) {
                    socket = null;
                    input = null;
                    output = null;
                    running.set(false);
                }
            }
            try {
                newSocket.close();
            } catch (IOException closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
    }

    public boolean isRunning() {
        Socket currentSocket = socket;
        return running.get() && currentSocket != null && !currentSocket.isClosed();
    }

    public void sendData(String data) throws IOException {
        Objects.requireNonNull(data, "data");
        sendData(data.getBytes(StandardCharsets.UTF_8));
    }

    public void sendData(byte[] data) throws IOException {
        Objects.requireNonNull(data, "data");
        if (data.length == 0 || data.length > maxMessageBytes) {
            throw new IOException("Invalid KnotLink message length: " + data.length);
        }

        synchronized (writeLock) {
            OutputStream currentOutput = output;
            if (!isRunning() || currentOutput == null) {
                throw new IOException("KnotLink TCP client is not connected");
            }

            if (frameFormat == FrameFormat.MAGIC_V2) {
                currentOutput.write(MAGIC);
            }
            currentOutput.write(ByteBuffer.allocate(LENGTH_BYTES).putInt(data.length).array());
            currentOutput.write(data);
            currentOutput.flush();
        }
    }

    public void setDataReceivedListener(DataReceivedListener listener) {
        dataReceivedListener = listener;
    }

    public void setErrorListener(Consumer<Throwable> listener) {
        errorListener = listener;
    }

    public void setClosedListener(Consumer<Throwable> listener) {
        closedListener = listener;
    }

    private void readLoop() {
        Throwable failure = null;
        try {
            while (running.get()) {
                byte[] message = readFrame();
                if (message == null) {
                    break;
                }
                if (Arrays.equals(message, HEARTBEAT_RESPONSE)) {
                    continue;
                }

                String decoded = decodeUtf8(message);
                DataReceivedListener listener = dataReceivedListener;
                if (listener != null) {
                    try {
                        listener.onDataReceived(decoded);
                    } catch (RuntimeException exception) {
                        notifyError(exception);
                    }
                }
            }
        } catch (IOException | RuntimeException exception) {
            if (running.get()) {
                failure = exception;
                notifyError(exception);
            }
        } finally {
            running.set(false);
            stopHeartbeat();
            closeSocket();
            notifyClosed(failure);
        }
    }

    private byte[] readFrame() throws IOException {
        InputStream currentInput = input;
        if (currentInput == null) {
            throw new IOException("KnotLink TCP input is unavailable");
        }

        int firstByte = currentInput.read();
        if (firstByte < 0) {
            return null;
        }

        int length;
        if (frameFormat == FrameFormat.MAGIC_V2) {
            byte[] magic = new byte[MAGIC.length];
            magic[0] = (byte) firstByte;
            readFully(currentInput, magic, 1, magic.length - 1);
            if (!Arrays.equals(magic, MAGIC)) {
                throw new IOException("Invalid KnotLink 2.0 magic header");
            }
            byte[] lengthBytes = new byte[LENGTH_BYTES];
            readFully(currentInput, lengthBytes, 0, lengthBytes.length);
            length = ByteBuffer.wrap(lengthBytes).getInt();
        } else {
            byte[] lengthBytes = new byte[LENGTH_BYTES];
            lengthBytes[0] = (byte) firstByte;
            readFully(currentInput, lengthBytes, 1, lengthBytes.length - 1);
            length = ByteBuffer.wrap(lengthBytes).getInt();
        }

        if (length <= 0 || length > maxMessageBytes) {
            throw new IOException("Invalid KnotLink message length: " + length);
        }
        byte[] message = new byte[length];
        readFully(currentInput, message, 0, message.length);
        return message;
    }

    private static void readFully(InputStream input, byte[] destination, int offset, int length)
            throws IOException {
        int total = 0;
        while (total < length) {
            int read = input.read(destination, offset + total, length - total);
            if (read < 0) {
                throw new EOFException("KnotLink connection closed during a frame");
            }
            total += read;
        }
    }

    private static String decodeUtf8(byte[] value) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("KnotLink frame is not valid UTF-8", exception);
        }
    }

    private void startHeartbeat() {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(
                    task,
                    "knotlink-sdk-heartbeat-" + THREAD_COUNTER.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        heartbeatExecutor = executor;
        long delayMillis = heartbeatInterval.toMillis();
        executor.scheduleWithFixedDelay(() -> {
            if (!running.get()) {
                return;
            }
            try {
                sendData(HEARTBEAT);
            } catch (IOException exception) {
                notifyError(exception);
                running.set(false);
                closeSocket();
            }
        }, delayMillis, delayMillis, TimeUnit.MILLISECONDS);
    }

    private void stopHeartbeat() {
        ScheduledExecutorService executor = heartbeatExecutor;
        heartbeatExecutor = null;
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void notifyError(Throwable error) {
        Consumer<Throwable> listener = errorListener;
        if (listener != null) {
            try {
                listener.accept(error);
            } catch (RuntimeException handlerFailure) {
                if (handlerFailure != error) {
                    handlerFailure.addSuppressed(error);
                }
                LOGGER.log(
                        System.Logger.Level.ERROR,
                        "KnotLink error listener failed",
                        handlerFailure);
            }
        }
    }

    private void notifyClosed(Throwable cause) {
        if (!closeNotified.compareAndSet(false, true)) {
            return;
        }
        Consumer<Throwable> listener = closedListener;
        if (listener != null) {
            try {
                listener.accept(cause);
            } catch (RuntimeException exception) {
                notifyError(exception);
            }
        }
    }

    private void closeSocket() {
        Socket currentSocket;
        synchronized (this) {
            currentSocket = socket;
            socket = null;
            input = null;
            output = null;
        }
        if (currentSocket == null) {
            return;
        }
        try {
            currentSocket.close();
        } catch (IOException exception) {
            notifyError(exception);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        running.set(false);
        stopHeartbeat();
        closeSocket();

        Thread reader = readThread;
        if (reader != null && reader != Thread.currentThread()) {
            try {
                reader.join(2_000L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        notifyClosed(null);
    }
}
