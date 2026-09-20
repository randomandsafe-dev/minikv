package com.paul.minikv.storage;

import java.util.Optional;

public interface StorageEngine {

    void set(String key, byte[] value);

    Optional<byte[]> get(String key);

    boolean delete(String key);

    boolean exists(String key);

    long size();
}