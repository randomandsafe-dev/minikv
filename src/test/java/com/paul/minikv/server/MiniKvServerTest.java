package com.paul.minikv.server;

import com.paul.minikv.protocol.*;
import com.paul.minikv.storage.InMemoryStorage;
import com.paul.minikv.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class MiniKvServerTest {

    @Test
    void shouldShareLruAcrossClientsAndReclaimExpiredKeysBeforeEviction() throws Exception {
        MutableClock clock = new MutableClock();
        InMemoryStorage storage = new InMemoryStorage(clock, 2);
        try (MiniKvServer server = new MiniKvServer("127.0.0.1", 0, storage).start();
             Socket first = connect(server); Socket second = connect(server)) {
            send(first, "SET", "a", "1");
            assertEquals(new RespSimpleString("OK"), read(first));
            send(first, "SET", "b", "2", "EX", "10");
            assertEquals(new RespSimpleString("OK"), read(first));
            send(second, "GET", "a");
            assertEquals(bulk("1"), read(second));
            send(second, "SET", "c", "3", "EX", "1");
            assertEquals(new RespSimpleString("OK"), read(second));
            send(first, "GET", "b");
            assertEquals(RespBulkString.nullValue(), read(first));
            send(first, "TTL", "b");
            assertEquals(new RespInteger(-2), read(first));
            send(first, "EXPIRE", "b", "100");
            assertEquals(new RespInteger(0), read(first));
            send(first, "EXISTS", "a", "c");
            assertEquals(new RespInteger(2), read(first));

            clock.advanceMillis(1000);
            send(first, "SET", "d", "4");
            assertEquals(new RespSimpleString("OK"), read(first));
            send(second, "EXISTS", "a", "d");
            assertEquals(new RespInteger(2), read(second));
            send(second, "GET", "c");
            assertEquals(RespBulkString.nullValue(), read(second));

            // Invalid writes at capacity must not evict a key or change its recency.
            send(second, "SET", "a", "bad", "EX", "9223372036854775807");
            assertInstanceOf(RespError.class, read(second));
            send(second, "SET", "invalid", "bad", "EX", "0");
            assertInstanceOf(RespError.class, read(second));
            send(second, "EXISTS", "a", "d", "invalid");
            assertEquals(new RespInteger(2), read(second));
            send(second, "SET", "e", "5");
            assertEquals(new RespSimpleString("OK"), read(second));
            send(first, "GET", "a");
            assertEquals(RespBulkString.nullValue(), read(first));
            send(first, "GET", "d");
            assertEquals(bulk("4"), read(first));
            send(first, "GET", "e");
            assertEquals(bulk("5"), read(first));
            assertEquals(2, storage.size());
        }
    }

    @Test
    void shouldShareExpiryAcrossClientsAndRecoverFromInvalidExpiry() throws Exception {
        MutableClock clock = new MutableClock();
        try (MiniKvServer server = new MiniKvServer("127.0.0.1", 0, new InMemoryStorage(clock)).start();
             Socket first = connect(server); Socket second = connect(server)) {
            send(first, "SET", "session", "token", "EX", "2");
            assertEquals(new RespSimpleString("OK"), read(first));
            send(second, "GET", "session");
            assertEquals(bulk("token"), read(second));
            send(second, "TTL", "session");
            assertEquals(new RespInteger(2), read(second));
            clock.advanceMillis(1000);
            send(second, "EXPIRE", "session", "3");
            assertEquals(new RespInteger(1), read(second));
            clock.advanceMillis(2999);
            send(first, "GET", "session");
            assertEquals(bulk("token"), read(first));
            clock.advanceMillis(1);
            send(first, "GET", "session");
            assertEquals(RespBulkString.nullValue(), read(first));
            send(second, "EXISTS", "session");
            assertEquals(new RespInteger(0), read(second));
            send(second, "TTL", "session");
            assertEquals(new RespInteger(-2), read(second));

            ByteArrayOutputStream pipeline = new ByteArrayOutputStream();
            for (byte[] request : new byte[][]{command("SET", "key", "value", "EX", "5"),
                    command("SET", "key", "bad", "EX", "0"), command("EXPIRE", "key", "1.5"),
                    command("GET", "key"), command("TTL", "key"), command("PING")}) {
                pipeline.writeBytes(request);
            }
            first.getOutputStream().write(pipeline.toByteArray());
            first.getOutputStream().flush();
            assertEquals(new RespSimpleString("OK"), read(first));
            assertInstanceOf(RespError.class, read(first));
            assertInstanceOf(RespError.class, read(first));
            assertEquals(bulk("value"), read(first));
            assertEquals(new RespInteger(5), read(first));
            assertEquals(new RespSimpleString("PONG"), read(first));
            send(second, "SET", "key", "persistent");
            assertEquals(new RespSimpleString("OK"), read(second));
            clock.advanceMillis(5000);
            send(first, "GET", "key");
            assertEquals(bulk("persistent"), read(first));
            send(first, "TTL", "key");
            assertEquals(new RespInteger(-1), read(first));
        }
    }

    @Test
    void shouldServeCrudOverTcpAndShareDataAcrossClients() throws Exception {
        try (MiniKvServer server = new MiniKvServer("127.0.0.1", 0, new InMemoryStorage()).start();
             Socket first = connect(server); Socket second = connect(server)) {
            send(first, "PING");
            assertEquals(new RespSimpleString("PONG"), read(first));
            send(first, "SET", "name", "MiniKV");
            assertEquals(new RespSimpleString("OK"), read(first));
            send(second, "GET", "name");
            assertEquals(bulk("MiniKV"), read(second));
            send(second, "EXISTS", "name");
            assertEquals(new RespInteger(1), read(second));
            send(second, "DEL", "name");
            assertEquals(new RespInteger(1), read(second));
            send(first, "GET", "name");
            assertEquals(RespBulkString.nullValue(), read(first));
        }
    }

    @Test
    void shouldHandleFragmentationPipelinesAndRecoverableErrorsOverTcp() throws Exception {
        try (MiniKvServer server = new MiniKvServer("127.0.0.1", 0, new InMemoryStorage()).start();
             Socket socket = connect(server)) {
            byte[] ping = command("PING");
            for (byte value : ping) {
                socket.getOutputStream().write(value);
                socket.getOutputStream().flush();
            }
            assertEquals(new RespSimpleString("PONG"), read(socket));
            ByteArrayOutputStream pipeline = new ByteArrayOutputStream();
            for (byte[] request : new byte[][]{command("SET", "x", "1"), command("GET", "x"),
                    command("GET"), command("UNKNOWN"), command("PING", "hello")}) {
                pipeline.writeBytes(request);
            }
            socket.getOutputStream().write(pipeline.toByteArray());
            assertEquals(new RespSimpleString("OK"), read(socket));
            assertEquals(bulk("1"), read(socket));
            assertInstanceOf(RespError.class, read(socket));
            assertInstanceOf(RespError.class, read(socket));
            assertEquals(bulk("hello"), read(socket));
        }
    }

    @Test
    void shouldIsolateBrokenConnectionAndServeConcurrentClients() throws Exception {
        try (MiniKvServer server = new MiniKvServer("127.0.0.1", 0, new InMemoryStorage()).start()) {
            try (Socket broken = connect(server)) {
                broken.getOutputStream().write("?bad\r\n".getBytes(StandardCharsets.US_ASCII));
                assertInstanceOf(RespError.class, read(broken));
                assertEquals(-1, broken.getInputStream().read());
            }
            try (var clients = Executors.newFixedThreadPool(4)) {
                var tasks = java.util.stream.IntStream.range(0, 12).mapToObj(index -> clients.submit(() -> {
                    try (Socket socket = connect(server)) {
                        send(socket, "SET", "key" + index, "value" + index);
                        assertEquals(new RespSimpleString("OK"), read(socket));
                        send(socket, "GET", "key" + index);
                        assertEquals(bulk("value" + index), read(socket));
                    }
                    return null;
                })).toList();
                for (var task : tasks) {
                    task.get(10, TimeUnit.SECONDS);
                }
            }
        }
    }

    @Test
    void shouldCloseClientsReleasePortAndCleanUpFailedBind() throws Exception {
        MiniKvServer server = new MiniKvServer("127.0.0.1", 0, new InMemoryStorage()).start();
        int port = server.port();
        try (server; Socket socket = connect(server)) {
            try (MiniKvServer duplicate = new MiniKvServer("127.0.0.1", port, new InMemoryStorage())) {
                assertThrows(IllegalStateException.class, duplicate::start);
                assertThrows(IllegalStateException.class, duplicate::start);
            }
            send(socket, "PING");
            assertEquals(new RespSimpleString("PONG"), read(socket));
            server.close();
            assertEquals(-1, socket.getInputStream().read());
        }
        try (MiniKvServer replacement = new MiniKvServer("127.0.0.1", port, new InMemoryStorage()).start();
             Socket socket = connect(replacement)) {
            send(socket, "PING");
            assertEquals(new RespSimpleString("PONG"), read(socket));
        }
    }

    private static Socket connect(MiniKvServer server) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", server.port()), 3000);
        socket.setSoTimeout(3000);
        return socket;
    }

    private static void send(Socket socket, String... parts) throws IOException {
        socket.getOutputStream().write(command(parts));
        socket.getOutputStream().flush();
    }

    private static byte[] command(String... parts) {
        return new RespEncoder().encode(RespArray.of(Arrays.stream(parts)
                .<RespValue>map(MiniKvServerTest::bulk).toList()));
    }

    private static RespBulkString bulk(String value) {
        return RespBulkString.of(value.getBytes(StandardCharsets.UTF_8));
    }

    private static RespValue read(Socket socket) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        RespDecoder decoder = new RespDecoder();
        while (true) {
            int value = socket.getInputStream().read();
            if (value == -1) {
                throw new IOException("connection closed before response");
            }
            bytes.write(value);
            try {
                return decoder.decode(bytes.toByteArray());
            } catch (RespIncompleteException ignored) {
                // Read exactly one response without consuming a pipelined reply.
            }
        }
    }
}
