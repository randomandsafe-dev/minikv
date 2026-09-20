package com.paul.minikv.storage;

import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryStorage implements StorageEngine {

    private final ConcurrentHashMap<String, byte[]> data =
            new ConcurrentHashMap<>();

    @Override
    public void set(String key, byte[] value) {
        validateKey(key);

        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }

        data.put(key, copy(value));
    }

    @Override
    public Optional<byte[]> get(String key) {
        validateKey(key);

        byte[] value = data.get(key);

        if (value == null) {
            return Optional.empty();
        }

        return Optional.of(copy(value));
    }

    @Override
    public boolean delete(String key) {
        validateKey(key);
        return data.remove(key) != null;
    }

    @Override
    public boolean exists(String key) {
        validateKey(key);
        return data.containsKey(key);
    }

    @Override
    public long size() {
        return data.mappingCount();
    }

    private static void validateKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException(
                    "key must not be null or blank"
            );
        }
    }

    private static byte[] copy(byte[] source) {
        return Arrays.copyOf(source, source.length);
    }
}
