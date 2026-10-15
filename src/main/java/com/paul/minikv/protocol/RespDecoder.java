package com.paul.minikv.protocol;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class RespDecoder {

    public RespValue decode(byte[] input) {
        Decoded decoded = decodeOne(input);
        if (decoded.bytesConsumed() != input.length) {
            throw new RespProtocolException(
                    "unexpected trailing bytes at index "
                            + decoded.bytesConsumed()
            );
        }
        return decoded.value();
    }

    public Decoded decodeOne(byte[] input) {
        Objects.requireNonNull(input, "input must not be null");
        if (input.length == 0) {
            throw new RespProtocolException("input must not be empty");
        }

        Cursor cursor = new Cursor(input);
        RespValue value = parseValue(cursor);
        return new Decoded(value, cursor.position);
    }

    private RespValue parseValue(Cursor cursor) {
        byte type = cursor.readByte("missing RESP type");
        return switch (type) {
            case '+' -> new RespSimpleString(cursor.readUtf8Line());
            case '-' -> new RespError(cursor.readUtf8Line());
            case ':' -> new RespInteger(parseNumber(cursor.readAsciiLine()));
            case '$' -> parseBulkString(cursor);
            case '*' -> parseArray(cursor);
            default -> throw new RespProtocolException(
                    "unsupported RESP type byte: " + printable(type)
            );
        };
    }

    private RespBulkString parseBulkString(Cursor cursor) {
        long length = parseLength(cursor.readAsciiLine(), "bulk string");
        if (length == -1) {
            return RespBulkString.nullValue();
        }
        if (length > Integer.MAX_VALUE) {
            throw new RespProtocolException("bulk string is too large");
        }

        byte[] value = cursor.readBytes((int) length);
        cursor.requireCrlf();
        return RespBulkString.of(value);
    }

    private RespArray parseArray(Cursor cursor) {
        long size = parseLength(cursor.readAsciiLine(), "array");
        if (size == -1) {
            return RespArray.nullValue();
        }
        if (size > Integer.MAX_VALUE) {
            throw new RespProtocolException("array is too large");
        }

        // Do not preallocate from untrusted network input. A malicious client
        // could otherwise force a large allocation before sending elements.
        List<RespValue> values = new ArrayList<>();
        for (int index = 0; index < size; index++) {
            values.add(parseValue(cursor));
        }
        return RespArray.of(values);
    }

    private static long parseLength(String text, String type) {
        long value = parseNumber(text);
        if (value < -1) {
            throw new RespProtocolException(
                    type + " length must be -1 or greater"
            );
        }
        return value;
    }

    private static long parseNumber(String text) {
        if (text.isEmpty() || text.charAt(0) == '+') {
            throw new RespProtocolException("invalid RESP number: " + text);
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException exception) {
            throw new RespProtocolException(
                    "invalid RESP number: " + text,
                    exception
            );
        }
    }

    private static String printable(byte value) {
        int unsigned = Byte.toUnsignedInt(value);
        if (unsigned >= 32 && unsigned <= 126) {
            return "'" + (char) unsigned + "'";
        }
        return "0x" + Integer.toHexString(unsigned);
    }

    public record Decoded(RespValue value, int bytesConsumed) {

        public Decoded {
            Objects.requireNonNull(value, "value must not be null");
            if (bytesConsumed < 1) {
                throw new IllegalArgumentException(
                        "bytesConsumed must be positive"
                );
            }
        }
    }

    private static final class Cursor {

        private final byte[] input;
        private int position;

        private Cursor(byte[] input) {
            this.input = input;
        }

        private byte readByte(String errorMessage) {
            if (position >= input.length) {
                throw new RespProtocolException(errorMessage);
            }
            return input[position++];
        }

        private String readAsciiLine() {
            return new String(readLine(), StandardCharsets.US_ASCII);
        }

        private String readUtf8Line() {
            return new String(readLine(), StandardCharsets.UTF_8);
        }

        private byte[] readLine() {
            int start = position;
            for (int index = position; index < input.length - 1; index++) {
                if (input[index] == '\r' && input[index + 1] == '\n') {
                    position = index + 2;
                    return Arrays.copyOfRange(input, start, index);
                }
            }
            throw new RespProtocolException(
                    "incomplete RESP line at index " + start
            );
        }

        private byte[] readBytes(int length) {
            if (input.length - position < length) {
                throw new RespProtocolException(
                        "bulk string payload is incomplete"
                );
            }
            byte[] result = Arrays.copyOfRange(
                    input,
                    position,
                    position + length
            );
            position += length;
            return result;
        }

        private void requireCrlf() {
            if (input.length - position < 2
                    || input[position] != '\r'
                    || input[position + 1] != '\n') {
                throw new RespProtocolException(
                        "bulk string payload must end with CRLF"
                );
            }
            position += 2;
        }
    }
}
