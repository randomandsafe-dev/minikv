package com.paul.minikv.command;

import com.paul.minikv.protocol.*;
import com.paul.minikv.storage.InMemoryStorage;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CommandExecutorTest {

    private final CommandExecutor executor = new CommandExecutor(new InMemoryStorage());

    @Test
    void shouldExecuteCrudAndOverwrite() {
        assertEquals(RespBulkString.nullValue(), execute("GET", "name"));
        assertEquals(new RespSimpleString("OK"), execute("SET", "name", "Alice"));
        assertEquals(bulk("Alice"), execute("GET", "name"));
        assertEquals(new RespSimpleString("OK"), execute("SET", "name", "Bob"));
        assertEquals(bulk("Bob"), execute("GET", "name"));
        assertEquals(new RespInteger(1), execute("EXISTS", "name"));
        assertEquals(new RespInteger(1), execute("DEL", "name"));
        assertEquals(new RespInteger(0), execute("DEL", "name"));
        assertEquals(new RespInteger(0), execute("EXISTS", "name"));
    }

    @Test
    void shouldSupportPingAndBinaryValues() {
        assertEquals(new RespSimpleString("PONG"), execute("ping"));
        byte[] binary = {0, '\r', '\n', (byte) 0xff};
        assertEquals(RespBulkString.of(binary), executor.execute(new RespCommand("PING", List.of(binary))));
        assertEquals(new RespSimpleString("OK"), executor.execute(
                new RespCommand("SET", List.of(bytes("名字"), binary))));
        assertEquals(RespBulkString.of(binary), execute("GET", "名字"));
        execute("SET", "empty", "");
        assertEquals(bulk(""), execute("GET", "empty"));
    }

    @Test
    void shouldCountMultipleAndRepeatedKeys() {
        execute("SET", "a", "1");
        execute("SET", "b", "2");
        assertEquals(new RespInteger(3), execute("EXISTS", "a", "a", "b", "missing"));
        assertEquals(new RespInteger(2), execute("DEL", "a", "a", "b", "missing"));
    }

    @Test
    void shouldRejectWrongArityAndUnknownCommands() {
        for (String name : List.of("GET", "SET", "DEL", "EXISTS")) {
            assertInstanceOf(RespError.class, execute(name));
        }
        assertInstanceOf(RespError.class, execute("GET", "a", "b"));
        assertInstanceOf(RespError.class, execute("SET", "a"));
        assertInstanceOf(RespError.class, execute("SET", "a", "b", "c"));
        assertInstanceOf(RespError.class, execute("PING", "a", "b"));
        assertInstanceOf(RespError.class, execute("NO\r\nSUCH"));
        assertEquals(new RespSimpleString("PONG"), execute("PING"));
    }

    @Test
    void shouldRejectInvalidKeysWithoutPartialDeletion() {
        execute("SET", "a", "value");
        assertInstanceOf(RespError.class, execute("DEL", "a", " "));
        assertEquals(bulk("value"), execute("GET", "a"));
        assertInstanceOf(RespError.class, execute("SET", "", "value"));
        assertInstanceOf(RespError.class, executor.execute(new RespCommand("GET",
                List.of(new byte[]{(byte) 0xff}))));
    }

    private RespValue execute(String name, String... arguments) {
        return executor.execute(new RespCommand(name, Arrays.stream(arguments)
                .map(CommandExecutorTest::bytes).toList()));
    }

    private static RespBulkString bulk(String value) {
        return RespBulkString.of(bytes(value));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
