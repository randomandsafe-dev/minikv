package com.paul.minikv.storage;

import java.util.Arrays;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;

/** TTL-aware storage with exact LRU eviction; all state is guarded by this instance's monitor. */
public final class InMemoryStorage implements StorageEngine {

    // Insertion order allows metadata lookups without touching LRU.
    // Only successful GET/SET move an entry to the most-recent end via putLast().
    private final LinkedHashMap<String, Entry> data = new LinkedHashMap<>();
    private final Clock clock;
    private final int maxEntries;

    public InMemoryStorage() {
        this(Clock.systemUTC());
    }

    public InMemoryStorage(Clock clock) {
        this(clock, Integer.MAX_VALUE);
    }

    public InMemoryStorage(int maxEntries) {
        this(Clock.systemUTC(), maxEntries);
    }

    /** Limits the number of stored keys, not the total bytes occupied by their values. */
    public InMemoryStorage(Clock clock, int maxEntries) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        this.maxEntries = maxEntries;
    }

    // Values are copied on entry/exit and never mutated within storage.
    private record Entry(byte[] value, Long expiresAtMillis) {
        boolean expired(long now) {
            return expiresAtMillis != null && now >= expiresAtMillis;
        }
    }

    @Override
    public synchronized void set(String key, byte[] value) {
        validateKey(key);

        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }

        store(key, new Entry(copy(value), null), clock.millis());
    }

    @Override
    public synchronized void set(String key, byte[] value, long seconds) {
        validateKey(key);
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
        if (seconds <= 0) {
            throw new IllegalArgumentException("invalid expire time in 'set' command");
        }
        byte[] stored = copy(value);
        long now = clock.millis();
        // Validate the deadline before changing data, LRU order, or evicting another key.
        store(key, new Entry(stored, deadline(now, seconds)), now);
    }

    @Override
    public synchronized boolean expire(String key, long seconds) {
        validateKey(key);
        long now = clock.millis();
        long deadline = seconds > 0 ? deadline(now, seconds) : now;
        Entry entry = liveEntry(key, now);
        if (entry == null) {
            return false;
        }
        if (seconds <= 0) {
            data.remove(key);
        } else {
            // Replacing an existing entry with put() preserves its LRU position.
            data.put(key, new Entry(entry.value(), deadline));
        }
        return true;
    }

    @Override
    public synchronized long ttl(String key) {
        validateKey(key);
        long now = clock.millis();
        Entry entry = liveEntry(key, now);
        if (entry == null) {
            return -2;
        }
        if (entry.expiresAtMillis() == null) {
            return -1;
        }
        long remaining;
        try {
            remaining = Math.subtractExact(entry.expiresAtMillis(), now);
        } catch (ArithmeticException exception) {
            // A clock moving far backwards must not make a live key appear expired.
            remaining = Long.MAX_VALUE;
        }
        return remaining / 1000 + (remaining % 1000 >= 500 ? 1 : 0);
    }

    @Override
    public synchronized Optional<byte[]> get(String key) {
        validateKey(key);

        Entry entry = liveEntry(key, clock.millis());

        if (entry == null) {
            return Optional.empty();
        }

        data.putLast(key, entry);
        return Optional.of(copy(entry.value()));
    }

    @Override
    public synchronized boolean delete(String key) {
        validateKey(key);
        long now = clock.millis();
        Entry entry = data.remove(key);
        return entry != null && !entry.expired(now);
    }

    @Override
    public synchronized boolean exists(String key) {
        validateKey(key);
        return liveEntry(key, clock.millis()) != null;
    }

    @Override
    public synchronized long size() {
        removeExpired(clock.millis());
        return data.size();
    }

    private void store(String key, Entry entry, long now) {
        if (!data.containsKey(key) && data.size() >= maxEntries) {
            // Expired keys may be anywhere in LRU order, including the newest end.
            removeExpired(now);
            if (data.size() >= maxEntries) {
                data.pollFirstEntry();
            }
        }
        data.putLast(key, entry);
    }

    private Entry liveEntry(String key, long now) {
        Entry entry = data.get(key);
        if (entry != null && entry.expired(now)) {
            data.remove(key);
            return null;
        }
        return entry;
    }

    private void removeExpired(long now) {
        data.values().removeIf(entry -> entry.expired(now));
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
