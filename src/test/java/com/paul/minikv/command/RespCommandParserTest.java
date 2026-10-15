package com.paul.minikv.command;

import com.paul.minikv.protocol.RespArray;
import com.paul.minikv.protocol.RespBulkString;
import com.paul.minikv.protocol.RespProtocolException;
import com.paul.minikv.protocol.RespSimpleString;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RespCommandParserTest {

    private final RespCommandParser parser = new RespCommandParser();

    @Test
    void shouldParseAndNormalizeCommand() {
        RespCommand command = parser.parse(RespArray.of(List.of(
                bulk("set"),
                bulk("name"),
                bulk("Alice")
        )));

        assertEquals("SET", command.name());
        assertEquals(2, command.argumentCount());
        assertArrayEquals(bytes("name"), command.argument(0));
        assertArrayEquals(bytes("Alice"), command.argument(1));
    }

    @Test
    void shouldDefensivelyCopyArguments() {
        byte[] original = bytes("Alice");
        RespCommand command = parser.parse(RespArray.of(List.of(
                bulk("SET"),
                RespBulkString.of(original)
        )));

        original[0] = 'X';
        byte[] returned = command.argument(0);
        returned[0] = 'Y';

        assertArrayEquals(bytes("Alice"), command.argument(0));
    }

    @Test
    void shouldRejectInvalidCommandShapes() {
        assertThrows(
                RespProtocolException.class,
                () -> parser.parse(RespArray.of(List.of()))
        );
        assertThrows(
                RespProtocolException.class,
                () -> parser.parse(new RespSimpleString("GET name"))
        );
        assertThrows(
                RespProtocolException.class,
                () -> parser.parse(RespArray.of(List.of(
                        bulk("GET"),
                        RespBulkString.nullValue()
                )))
        );
    }

    private static RespBulkString bulk(String value) {
        return RespBulkString.of(bytes(value));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
