package com.paul.minikv.command;

import com.paul.minikv.protocol.*;
import com.paul.minikv.storage.InMemoryStorage;
import com.paul.minikv.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CommandExecutorTest {

    private final MutableClock clock = new MutableClock();
    private final CommandExecutor executor = new CommandExecutor(new InMemoryStorage(clock));

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
        for (String name : List.of("GET", "SET", "DEL", "EXISTS", "EXPIRE", "TTL")) {
            assertInstanceOf(RespError.class, execute(name));
        }
        assertInstanceOf(RespError.class, execute("GET", "a", "b"));
        assertInstanceOf(RespError.class, execute("SET", "a"));
        assertInstanceOf(RespError.class, execute("SET", "a", "b", "c"));
        assertInstanceOf(RespError.class, execute("SET", "a", "b", "EX", "1", "EX", "2"));
        assertInstanceOf(RespError.class, execute("EXPIRE", "a"));
        assertInstanceOf(RespError.class, execute("EXPIRE", "a", "1", "NX"));
        assertInstanceOf(RespError.class, execute("TTL", "a", "b"));
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

    @Test
    void shouldExecuteExpiryCommandsAndResetTtlOnOverwrite() {
        assertEquals(new RespInteger(-2), execute("TTL", "key"));
        assertEquals(new RespInteger(0), execute("EXPIRE", "key", "1"));
        assertEquals(new RespSimpleString("OK"), execute("set", "key", "value", "ex", "2"));
        assertEquals(new RespInteger(2), execute("ttl", "key"));
        clock.advanceMillis(1000);
        assertEquals(new RespInteger(1), execute("expire", "key", "3"));
        clock.advanceMillis(1000);
        assertEquals(bulk("value"), execute("GET", "key"));
        assertEquals(new RespInteger(2), execute("TTL", "key"));
        execute("SET", "key", "replacement");
        clock.advanceMillis(3000);
        assertEquals(bulk("replacement"), execute("GET", "key"));
        assertEquals(new RespInteger(-1), execute("TTL", "key"));
        assertEquals(new RespInteger(1), execute("EXPIRE", "key", "0"));
        assertEquals(RespBulkString.nullValue(), execute("GET", "key"));
        assertEquals(new RespInteger(-2), execute("TTL", "key"));
    }

    @Test
    void shouldExcludeExpiredKeysFromMultiKeyCounts() {
        execute("SET", "expired", "value", "EX", "1");
        execute("SET", "live", "value");
        clock.advanceMillis(1000);
        assertEquals(new RespInteger(1), execute("EXISTS", "expired", "expired", "live"));
        assertEquals(new RespInteger(1), execute("DEL", "expired", "live", "live"));
        assertEquals(new RespInteger(0), execute("EXPIRE", "expired", "2"));
    }

    @Test
    void shouldRejectInvalidSecondsAndOptionsWithoutMutatingExistingKey() {
        execute("SET", "key", "original", "EX", "10");
        for (String seconds : List.of("", "1.5", " 1", "1 ", "abc", "１", "1\r\n",
                "9223372036854775808", "9223372036854775807", "9223372036854775")) {
            assertInstanceOf(RespError.class, execute("SET", "key", "bad", "EX", seconds));
            assertInstanceOf(RespError.class, execute("EXPIRE", "key", seconds));
            assertEquals(bulk("original"), execute("GET", "key"));
            assertEquals(new RespInteger(10), execute("TTL", "key"));
        }
        for (String seconds : List.of("0", "-1")) {
            assertInstanceOf(RespError.class, execute("SET", "key", "bad", "EX", seconds));
        }
        assertInstanceOf(RespError.class, execute("SET", "key", "bad", "PX", "10"));
        assertEquals(bulk("original"), execute("GET", "key"));
        assertEquals(new RespInteger(10), execute("TTL", "key"));
        assertInstanceOf(RespError.class, execute("EXPIRE", " ", "1"));
        assertInstanceOf(RespError.class, execute("TTL", " "));
        assertEquals(new RespSimpleString("PONG"), execute("PING"));
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
