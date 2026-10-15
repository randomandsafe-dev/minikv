package com.paul.minikv.protocol;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RespDecoderTest {

    private final RespDecoder decoder = new RespDecoder();

    @Test
    void shouldDecodeSimpleTypes() {
        assertEquals(
                new RespSimpleString("OK"),
                decoder.decode(ascii("+OK\r\n"))
        );
        assertEquals(
                new RespError("ERR failure"),
                decoder.decode(ascii("-ERR failure\r\n"))
        );
        assertEquals(
                new RespInteger(-7),
                decoder.decode(ascii(":-7\r\n"))
        );
    }

    @Test
    void shouldDecodeBinarySafeBulkString() {
        byte[] encoded = {'$', '4', '\r', '\n', 0, '\r', '\n', 1, '\r', '\n'};

        RespBulkString bulk = assertInstanceOf(
                RespBulkString.class,
                decoder.decode(encoded)
        );

        assertArrayEquals(
                new byte[]{0, '\r', '\n', 1},
                bulk.value().orElseThrow()
        );
    }

    @Test
    void shouldDecodeNullValues() {
        RespBulkString bulk = assertInstanceOf(
                RespBulkString.class,
                decoder.decode(ascii("$-1\r\n"))
        );
        RespArray array = assertInstanceOf(
                RespArray.class,
                decoder.decode(ascii("*-1\r\n"))
        );

        assertTrue(bulk.isNull());
        assertTrue(array.isNull());
    }

    @Test
    void shouldDecodeCommandArray() {
        RespValue decoded = decoder.decode(ascii(
                "*3\r\n$3\r\nSET\r\n$4\r\nname\r\n$5\r\nAlice\r\n"
        ));

        assertEquals(
                RespArray.of(List.of(
                        RespBulkString.of(ascii("SET")),
                        RespBulkString.of(ascii("name")),
                        RespBulkString.of(ascii("Alice"))
                )),
                decoded
        );
    }

    @Test
    void shouldReportBytesConsumedForFirstValue() {
        RespDecoder.Decoded decoded = decoder.decodeOne(
                ascii("+OK\r\n:1\r\n")
        );

        assertEquals(new RespSimpleString("OK"), decoded.value());
        assertEquals(5, decoded.bytesConsumed());
    }

    @Test
    void shouldRejectIncompleteAndMalformedInput() {
        assertThrows(
                RespProtocolException.class,
                () -> decoder.decode(ascii("$5\r\nabc\r\n"))
        );
        assertThrows(
                RespProtocolException.class,
                () -> decoder.decode(ascii("$-2\r\n"))
        );
        assertThrows(
                RespProtocolException.class,
                () -> decoder.decode(ascii("?hello\r\n"))
        );
        assertThrows(
                RespProtocolException.class,
                () -> decoder.decode(ascii("+OK\r\n:1\r\n"))
        );
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }
}
