package com.leafuke.minebackup.knotlink;

import com.leafuke.minebackup.api.v2.OperationFailure;
import com.leafuke.minebackup.knotlink.protocol.KnotLinkRequest;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KnotLinkDiagnosticsTest {
    private static final KnotLinkRequest REQUEST = KnotLinkRequest.command("GET_STATUS");

    @Test void refusedPortAndControlledConnectTimeoutAreServiceFailures() throws Exception {
        int port;
        try (var reserve = new ServerSocket(0, 10, InetAddress.getLoopbackAddress())) { port = reserve.getLocalPort(); }
        try (var client = new KnotLinkClient("127.0.0.1", port, 100, 100)) {
            assertFailure(OperationFailure.Code.KNOTLINK_UNREACHABLE, client.query(REQUEST));
        }
        try (var client = new KnotLinkClient(() -> { throw new SocketTimeoutException("controlled connect timeout"); })) {
            assertFailure(OperationFailure.Code.KNOTLINK_UNREACHABLE, client.query(REQUEST));
        }
    }

    @Test void offlineIsRecognizedBeforeProtocolParsing() throws Exception {
        try (var server = new Peer(socket -> send(socket, "offline")); var client = server.client()) {
            assertFailure(OperationFailure.Code.BACKEND_OFFLINE, client.query(REQUEST));
        }
    }

    @Test void validRepliesAndBackendRejectionsAreNotTransportFailures() throws Exception {
        for (String response : new String[] {"status=ok;data=enabled%3DTrue", "status=error;message=Rejected"}) {
            try (var server = new Peer(socket -> send(socket, response)); var client = server.client()) {
                assertEquals(response.startsWith("status=ok"), client.query(REQUEST).get(2, TimeUnit.SECONDS).isOk());
            }
        }
    }

    @Test void responseTimeoutAndDisconnectAreDistinct() throws Exception {
        try (var server = new Peer(socket -> { }); var client = server.client()) {
            assertFailure(OperationFailure.Code.RESPONSE_TIMEOUT, client.query(REQUEST));
        }
        try (var server = new Peer(Socket::close); var client = server.client()) {
            assertFailure(OperationFailure.Code.CONNECTION_CLOSED, client.query(REQUEST));
        }
    }

    @Test void malformedPayloadAndFramesAreProtocolFailures() throws Exception {
        try (var server = new Peer(socket -> send(socket, "not a v2 response")); var client = server.client()) {
            assertFailure(OperationFailure.Code.PROTOCOL_ERROR, client.query(REQUEST));
        }
        try (var server = new Peer(socket -> {
            new DataOutputStream(socket.getOutputStream()).writeLong(0);
            socket.getOutputStream().flush();
        }); var client = server.client()) {
            assertFailure(OperationFailure.Code.PROTOCOL_ERROR, client.query(REQUEST));
        }
    }

    @Test void boundedQueueAndShutdownCompleteEveryPendingQuery() throws Exception {
        try (var server = new Peer(socket -> { }); var client = new KnotLinkClient("127.0.0.1", server.port(), 1000, 30000)) {
            List<CompletableFuture<?>> pending = new ArrayList<>();
            for (int i = 0; i < 68; i++) pending.add(client.query(REQUEST));
            assertFailure(OperationFailure.Code.QUERY_QUEUE_FULL, client.query(REQUEST));
            client.close();
            for (var future : pending) assertTrue(future.isDone(), "Shutdown must complete queued queries");
            assertFailure(OperationFailure.Code.CLIENT_CLOSED, client.query(REQUEST));
        }
    }

    private static void assertFailure(OperationFailure.Code expected, CompletableFuture<?> query) {
        Exception error = assertThrows(Exception.class, () -> query.get(2, TimeUnit.SECONDS));
        assertEquals(expected, KnotLinkCommunicationException.failure(error).code());
    }

    private static void send(Socket socket, String payload) throws IOException {
        var input = new DataInputStream(socket.getInputStream());
        assertEquals(0x4b4b0002, input.readInt());
        int length = input.readInt();
        input.readNBytes(length);
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        var output = new DataOutputStream(socket.getOutputStream());
        output.writeInt(0x4b4b0002);
        output.writeInt(bytes.length);
        output.write(bytes);
        output.flush();
    }

    @FunctionalInterface interface Handler { void accept(Socket socket) throws Exception; }
    private static final class Peer implements AutoCloseable {
        private final ServerSocket server;
        private final List<Socket> sockets = new CopyOnWriteArrayList<>();
        Peer(Handler handler) throws IOException {
            server = new ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"));
            Thread accept = new Thread(() -> {
                while (!server.isClosed()) {
                    try {
                        Socket socket = server.accept();
                        sockets.add(socket);
                        handler.accept(socket);
                    } catch (Exception ignored) { }
                }
            });
            accept.setDaemon(true);
            accept.start();
        }
        int port() { return server.getLocalPort(); }
        KnotLinkClient client() { return new KnotLinkClient("127.0.0.1", port(), 1000, 100); }
        @Override public void close() throws IOException {
            server.close();
            for (Socket socket : sockets) socket.close();
        }
    }
}
