package com.paul.minikv.storage;

import java.util.Arrays;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryStorage implements StorageEngine {

    private final ConcurrentHashMap<String, Entry> data =
            new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryStorage() {
        this(Clock.systemUTC());
    }

    public InMemoryStorage(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    // Values are copied on entry/exit and never mutated within storage.
    private record Entry(byte[] value, Long expiresAtMillis) {
        boolean expired(long now) {
            return expiresAtMillis != null && now >= expiresAtMillis;
        }
    }

    @Override
    public void set(String key, byte[] value) {
        validateKey(key);

        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }

        data.put(key, new Entry(copy(value), null));
    }

    @Override
    public void set(String key, byte[] value, long seconds) {
        validateKey(key);
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
        if (seconds <= 0) {
            throw new IllegalArgumentException("invalid expire time in 'set' command");
        }
        byte[] stored = copy(value);
        data.compute(key, (ignored, previous) ->
                new Entry(stored, deadline(clock.millis(), seconds)));
    }

    @Override
    public boolean expire(String key, long seconds) {
        validateKey(key);
        boolean[] updated = {false};
        data.compute(key, (ignored, entry) -> {
            long now = clock.millis();
            long deadline = seconds > 0 ? deadline(now, seconds) : now;
            if (entry == null || entry.expired(now)) {
                return null;
            }
            updated[0] = true;
            return seconds <= 0 ? null : new Entry(entry.value(), deadline);
        });
        return updated[0];
    }

    @Override
    public long ttl(String key) {
        validateKey(key);
        long[] result = {-2};
        data.computeIfPresent(key, (ignored, entry) -> {
            long now = clock.millis();
            if (entry.expired(now)) {
                return null;
            }
            if (entry.expiresAtMillis() == null) {
                result[0] = -1;
            } else {
                long remaining;
                try {
                    remaining = Math.subtractExact(entry.expiresAtMillis(), now);
                } catch (ArithmeticException exception) {
                    // A clock moving far backwards must not make a live key appear expired.
                    remaining = Long.MAX_VALUE;
                }
                result[0] = remaining / 1000 + (remaining % 1000 >= 500 ? 1 : 0);
            }
            return entry;
        });
        return result[0];
    }

    @Override
    public Optional<byte[]> get(String key) {
        validateKey(key);

        Entry entry = liveEntry(key);

        if (entry == null) {
            return Optional.empty();
        }

        return Optional.of(copy(entry.value()));
    }

    @Override
    public boolean delete(String key) {
        validateKey(key);
        boolean[] deleted = {false};
        data.computeIfPresent(key, (ignored, entry) -> {
            deleted[0] = !entry.expired(clock.millis());
            return null;
        });
        return deleted[0];
    }

    @Override
    public boolean exists(String key) {
        validateKey(key);
        return liveEntry(key) != null;
    }

    @Override
    public long size() {
        long count = 0;
        for (String key : data.keySet()) {
            if (liveEntry(key) != null) {
                count++;
            }
        }
        return count;
    }

    private Entry liveEntry(String key) {
        // Cleanup and writes to the same key are serialized by ConcurrentHashMap.
        return data.computeIfPresent(key, (ignored, entry) ->
                entry.expired(clock.millis()) ? null : entry);
    }

    private static long deadline(long now, long seconds) {
        try {
            return Math.addExact(now, Math.multiplyExact(seconds, 1000));
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("expire time is out of range", exception);
        }
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
