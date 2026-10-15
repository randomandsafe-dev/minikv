package com.paul.minikv.protocol;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

public final class RespBulkString implements RespValue {

    private static final RespBulkString NULL = new RespBulkString(null);

    private final byte[] value;

    private RespBulkString(byte[] value) {
        this.value = value == null ? null : Arrays.copyOf(value, value.length);
    }

    public static RespBulkString of(byte[] value) {
        Objects.requireNonNull(value, "value must not be null");
        return new RespBulkString(value);
    }

    public static RespBulkString nullValue() {
        return NULL;
    }

    public boolean isNull() {
        return value == null;
    }

    public Optional<byte[]> value() {
        if (value == null) {
            return Optional.empty();
        }
        return Optional.of(Arrays.copyOf(value, value.length));
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof RespBulkString that
                && Arrays.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(value);
    }

    @Override
    public String toString() {
        return value == null
                ? "RespBulkString[null]"
                : "RespBulkString[length=" + value.length + "]";
    }
}
