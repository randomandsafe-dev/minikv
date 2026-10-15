package com.paul.minikv.command;

import com.paul.minikv.protocol.RespArray;
import com.paul.minikv.protocol.RespBulkString;
import com.paul.minikv.protocol.RespProtocolException;
import com.paul.minikv.protocol.RespValue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class RespCommandParser {

    public RespCommand parse(RespValue value) {
        if (!(value instanceof RespArray array) || array.isNull()) {
            throw new RespProtocolException(
                    "command must be a non-null RESP array"
            );
        }

        List<RespValue> elements = array.values().orElseThrow();
        if (elements.isEmpty()) {
            throw new RespProtocolException("command array must not be empty");
        }

        List<byte[]> parts = new ArrayList<>(elements.size());
        for (RespValue element : elements) {
            if (!(element instanceof RespBulkString bulk) || bulk.isNull()) {
                throw new RespProtocolException(
                        "command elements must be non-null bulk strings"
                );
            }
            parts.add(bulk.value().orElseThrow());
        }

        String name = new String(parts.removeFirst(), StandardCharsets.US_ASCII);
        if (name.isBlank()) {
            throw new RespProtocolException("command name must not be blank");
        }

        return new RespCommand(name.toUpperCase(Locale.ROOT), parts);
    }
}
