package com.paul.minikv.protocol;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class RespArray implements RespValue {

    private static final RespArray NULL = new RespArray(null);

    private final List<RespValue> values;

    private RespArray(List<RespValue> values) {
        this.values = values == null ? null : List.copyOf(values);
    }

    public static RespArray of(List<? extends RespValue> values) {
        Objects.requireNonNull(values, "values must not be null");
        return new RespArray(values.stream()
                .map(value -> Objects.requireNonNull(
                        value,
                        "array element must not be null"
                ))
                .toList());
    }

    public static RespArray nullValue() {
        return NULL;
    }

    public boolean isNull() {
        return values == null;
    }

    public Optional<List<RespValue>> values() {
        return Optional.ofNullable(values);
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof RespArray that
                && Objects.equals(values, that.values);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(values);
    }

    @Override
    public String toString() {
        return values == null
                ? "RespArray[null]"
                : "RespArray" + values;
    }
}
