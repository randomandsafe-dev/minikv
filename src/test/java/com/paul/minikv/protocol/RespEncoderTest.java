package com.paul.minikv.protocol;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class RespEncoderTest {

    private final RespEncoder encoder = new RespEncoder();

    @Test
    void shouldEncodeSimpleTypes() {
        assertEncoded("+OK\r\n", new RespSimpleString("OK"));
        assertEncoded("-ERR unknown command\r\n",
                new RespError("ERR unknown command"));
        assertEncoded(":42\r\n", new RespInteger(42));
    }

    @Test
    void shouldEncodeBinarySafeBulkString() {
        byte[] value = {'a', '\r', '\n', 0, 'b'};

        assertArrayEquals(
                concat(ascii("$5\r\n"), value, ascii("\r\n")),
                encoder.encode(RespBulkString.of(value))
        );
    }

    @Test
    void shouldEncodeNullBulkString() {
        assertEncoded("$-1\r\n", RespBulkString.nullValue());
    }

    @Test
    void shouldEncodeNestedArray() {
        RespArray value = RespArray.of(List.of(
                RespBulkString.of(ascii("GET")),
                RespBulkString.of(ascii("name")),
                RespArray.of(List.of(new RespInteger(1)))
        ));

        assertEncoded(
                "*3\r\n$3\r\nGET\r\n$4\r\nname\r\n*1\r\n:1\r\n",
                value
        );
    }

    @Test
    void shouldEncodeNullArray() {
        assertEncoded("*-1\r\n", RespArray.nullValue());
    }

    private void assertEncoded(String expected, RespValue value) {
        assertArrayEquals(ascii(expected), encoder.encode(value));
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] concat(byte[]... parts) {
        int size = 0;
        for (byte[] part : parts) {
            size += part.length;
        }

        byte[] result = new byte[size];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }
}
