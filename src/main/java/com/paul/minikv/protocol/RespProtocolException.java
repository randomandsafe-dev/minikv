package com.paul.minikv.protocol;

public final class RespProtocolException extends IllegalArgumentException {

    public RespProtocolException(String message) {
        super(message);
    }

    public RespProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
