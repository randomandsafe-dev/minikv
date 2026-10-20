package com.paul.minikv.command;

import com.paul.minikv.protocol.*;
import com.paul.minikv.storage.StorageEngine;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Executes the supported RESP2 command subset against shared storage. */
public final class CommandExecutor {

    private final StorageEngine storage;

    public CommandExecutor(StorageEngine storage) {
        this.storage = Objects.requireNonNull(storage, "storage must not be null");
    }

    public RespValue execute(RespCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        String name = command.name().toUpperCase(Locale.ROOT);
        int count = command.argumentCount();
        boolean validArity = switch (name) {
            case "PING" -> count <= 1;
            case "SET" -> count == 2 || count == 4;
            case "EXPIRE" -> count == 2;
            case "GET", "TTL" -> count == 1;
            case "DEL", "EXISTS" -> count >= 1;
            default -> true;
        };
        if (!validArity) {
            return new RespError("ERR wrong number of arguments for '"
                    + name.toLowerCase(Locale.ROOT) + "' command");
        }

        try {
            return switch (name) {
                case "PING" -> count == 0 ? new RespSimpleString("PONG")
                        : RespBulkString.of(command.argument(0));
                case "SET" -> {
                    String key = key(command.argument(0));
                    if (count == 2) {
                        storage.set(key, command.argument(1));
                    } else {
                        if (!new String(command.argument(2), StandardCharsets.US_ASCII).equalsIgnoreCase("EX")) {
                            throw new IllegalArgumentException("syntax error");
                        }
                        storage.set(key, command.argument(1), seconds(command.argument(3)));
                    }
                    yield new RespSimpleString("OK");
                }
                case "EXPIRE" -> new RespInteger(storage.expire(key(command.argument(0)),
                        seconds(command.argument(1))) ? 1 : 0);
                case "TTL" -> new RespInteger(storage.ttl(key(command.argument(0))));
                case "GET" -> storage.get(key(command.argument(0)))
                        .map(RespBulkString::of).orElseGet(RespBulkString::nullValue);
                case "DEL", "EXISTS" -> {
                    // Validate every key before DEL changes any data.
                    List<String> keys = new ArrayList<>();
                    for (byte[] argument : command.arguments()) {
                        keys.add(key(argument));
                    }
                    long result = 0;
                    for (String key : keys) {
                        if (name.equals("DEL") ? storage.delete(key) : storage.exists(key)) {
                            result++;
                        }
                    }
                    yield new RespInteger(result);
                }
                default -> new RespError("ERR unknown command");
            };
        } catch (IllegalArgumentException exception) {
            return new RespError("ERR " + exception.getMessage().replace('\r', ' ').replace('\n', ' '));
        }
    }

    private static long seconds(byte[] bytes) {
        String value = new String(bytes, StandardCharsets.US_ASCII);
        if (!value.matches("-?[0-9]+")) {
            throw new IllegalArgumentException("value is not an integer or out of range");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("value is not an integer or out of range", exception);
        }
    }

    private static String key(byte[] bytes) {
        final String key;
        try {
            key = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("key must be valid UTF-8", exception);
        }
        if (key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        return key;
    }
}
