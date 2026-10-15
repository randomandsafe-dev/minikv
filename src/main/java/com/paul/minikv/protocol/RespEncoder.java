package com.paul.minikv.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

public final class RespEncoder {

    private static final byte[] CRLF = {'\r', '\n'};

    public byte[] encode(RespValue value) {
        Objects.requireNonNull(value, "value must not be null");

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        write(value, output);
        return output.toByteArray();
    }

    private void write(RespValue value, ByteArrayOutputStream output) {
        switch (value) {
            case RespSimpleString simple -> {
                output.write('+');
                writeUtf8(simple.value(), output);
                output.writeBytes(CRLF);
            }
            case RespError error -> {
                output.write('-');
                writeUtf8(error.message(), output);
                output.writeBytes(CRLF);
            }
            case RespInteger integer -> {
                output.write(':');
                writeAscii(Long.toString(integer.value()), output);
                output.writeBytes(CRLF);
            }
            case RespBulkString bulk -> writeBulkString(bulk, output);
            case RespArray array -> writeArray(array, output);
        }
    }

    private void writeBulkString(
            RespBulkString bulk,
            ByteArrayOutputStream output
    ) {
        output.write('$');
        if (bulk.isNull()) {
            writeAscii("-1", output);
            output.writeBytes(CRLF);
            return;
        }

        byte[] bytes = bulk.value().orElseThrow();
        writeAscii(Integer.toString(bytes.length), output);
        output.writeBytes(CRLF);
        output.writeBytes(bytes);
        output.writeBytes(CRLF);
    }

    private void writeArray(RespArray array, ByteArrayOutputStream output) {
        output.write('*');
        if (array.isNull()) {
            writeAscii("-1", output);
            output.writeBytes(CRLF);
            return;
        }

        List<RespValue> values = array.values().orElseThrow();
        writeAscii(Integer.toString(values.size()), output);
        output.writeBytes(CRLF);
        values.forEach(value -> write(value, output));
    }

    private static void writeAscii(
            String value,
            ByteArrayOutputStream output
    ) {
        output.writeBytes(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static void writeUtf8(
            String value,
            ByteArrayOutputStream output
    ) {
        output.writeBytes(value.getBytes(StandardCharsets.UTF_8));
    }
}
