package com.paul.minikv.protocol;

/** The input is a valid prefix; a streaming caller should wait for more bytes. */
public final class RespIncompleteException extends RespProtocolException {

    public RespIncompleteException(String message) {
        super(message);
    }
}
