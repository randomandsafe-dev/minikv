package com.paul.minikv.protocol;

import java.util.Objects;

public record RespSimpleString(String value) implements RespValue {

    public RespSimpleString {
        Objects.requireNonNull(value, "value must not be null");
        rejectLineBreak(value);
    }

    private static void rejectLineBreak(String value) {
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(
                    "simple string must not contain CR or LF"
            );
        }
    }
}
