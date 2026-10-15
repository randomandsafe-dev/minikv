package com.paul.minikv.protocol;

/**
 * A value supported by the Redis Serialization Protocol (RESP2).
 */
public sealed interface RespValue permits RespSimpleString, RespError,
        RespInteger, RespBulkString, RespArray {
}
