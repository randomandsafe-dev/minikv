package com.paul.minikv.storage;

import java.util.Optional;

public interface StorageEngine {

    void set(String key, byte[] value);

    /** Atomically sets a value with a strictly positive lifetime in seconds. */
    void set(String key, byte[] value, long seconds);

    /** Sets/replaces a live key's lifetime; non-positive seconds delete it immediately. */
    boolean expire(String key, long seconds);

    /** Remaining seconds (rounded to nearest second), -1 if persistent, -2 if absent. */
    long ttl(String key);

    Optional<byte[]> get(String key);

    boolean delete(String key);

    boolean exists(String key);

    /** Cleans up expired entries and counts live keys; weakly consistent during concurrent writes. */
    long size();
}
