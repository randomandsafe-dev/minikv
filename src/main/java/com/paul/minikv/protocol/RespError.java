package com.paul.minikv.protocol;

import java.util.Objects;

public record RespError(String message) implements RespValue {

    public RespError {
        Objects.requireNonNull(message, "message must not be null");
        if (message.indexOf('\r') >= 0 || message.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(
                    "error message must not contain CR or LF"
            );
        }
    }
}
