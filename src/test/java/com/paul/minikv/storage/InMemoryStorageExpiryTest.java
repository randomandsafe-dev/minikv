package com.paul.minikv.storage;

import com.paul.minikv.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryStorageExpiryTest {
    private final MutableClock clock = new MutableClock();
    private final StorageEngine storage = new InMemoryStorage(clock);
    private final byte[] value = {1, 2, 3};

    @Test
    void shouldDistinguishMissingPersistentAndExpiringKeys() {
        assertEquals(-2, storage.ttl("missing"));
        assertFalse(storage.expire("missing", 10));
        storage.set("key", value);
        assertEquals(-1, storage.ttl("key"));
        assertTrue(storage.expire("key", 2));
        assertEquals(2, storage.ttl("key"));
        clock.advanceMillis(1500);
        assertEquals(1, storage.ttl("key"));
        clock.advanceMillis(499);
        assertEquals(0, storage.ttl("key"));
        assertArrayEquals(value, storage.get("key").orElseThrow());
        clock.advanceMillis(1);
        assertEquals(-2, storage.ttl("key"));
        assertFalse(storage.expire("key", 10));
    }

    @Test
    void shouldTreatExpiredEntriesAsAbsentForEveryOperation() {
        for (String key : List.of("get", "exists", "delete", "expire", "ttl", "size")) {
            storage.set(key, value, 1);
        }
        clock.advanceMillis(1000);
        assertTrue(storage.get("get").isEmpty());
        assertFalse(storage.exists("exists"));
        assertFalse(storage.delete("delete"));
        assertFalse(storage.expire("expire", 10));
        assertEquals(-2, storage.ttl("ttl"));
        storage.set("persistent", value);
        assertEquals(1, storage.size());
    }

    @Test
    void shouldReplaceExpiryAndClearItOnPlainSet() {
        storage.set("key", value, 1);
        clock.advanceMillis(500);
        assertTrue(storage.expire("key", 2));
        clock.advanceMillis(500);
        assertTrue(storage.exists("key"));
        assertEquals(2, storage.ttl("key"));
        storage.set("key", new byte[]{4});
        clock.advanceMillis(2000);
        assertArrayEquals(new byte[]{4}, storage.get("key").orElseThrow());
        assertEquals(-1, storage.ttl("key"));
        storage.set("key", value, 1);
        clock.advanceMillis(1000);
        storage.set("key", new byte[]{5}, 2);
        assertEquals(2, storage.ttl("key"));
        assertArrayEquals(new byte[]{5}, storage.get("key").orElseThrow());
    }

    @Test
    void shouldDeleteImmediatelyForNonPositiveExpire() {
        for (long seconds : new long[]{0, -1, Long.MIN_VALUE}) {
            storage.set("key", value);
            assertTrue(storage.expire("key", seconds));
            assertFalse(storage.exists("key"));
            assertFalse(storage.expire("key", seconds));
        }
    }

    @Test
    void shouldRejectInvalidExpiryWithoutChangingValueOrDeadline() {
        storage.set("key", value, 10);
        for (long seconds : new long[]{0, -1, Long.MIN_VALUE, Long.MAX_VALUE, Long.MAX_VALUE / 1000}) {
            assertThrows(IllegalArgumentException.class, () -> storage.set("key", new byte[]{4}, seconds));
            assertArrayEquals(value, storage.get("key").orElseThrow());
            assertEquals(10, storage.ttl("key"));
        }
        assertThrows(IllegalArgumentException.class, () -> storage.expire("key", Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> storage.expire("key", Long.MAX_VALUE / 1000));
        assertEquals(10, storage.ttl("key"));
    }

    @Test
    void shouldSupportLargestRepresentableDeadlineWithoutRoundingOverflow() {
        StorageEngine nearEpoch = new InMemoryStorage(Clock.fixed(Instant.ofEpochMilli(807), ZoneOffset.UTC));
        nearEpoch.set("key", value, Long.MAX_VALUE / 1000);
        assertEquals(Long.MAX_VALUE / 1000, nearEpoch.ttl("key"));
    }

    @Test
    void shouldValidateKeysAndValuesForExpiryOperations() {
        for (String key : new String[]{null, "", " "}) {
            assertThrows(IllegalArgumentException.class, () -> storage.set(key, value, 1));
            assertThrows(IllegalArgumentException.class, () -> storage.expire(key, 1));
            assertThrows(IllegalArgumentException.class, () -> storage.ttl(key));
        }
        assertThrows(IllegalArgumentException.class, () -> storage.set("key", null, 1));
    }

    @Test
    void shouldPreserveDefensiveCopiesWhenSettingAndReplacingExpiry() {
        storage.set("key", value, 1);
        value[0] = 9;
        storage.get("key").orElseThrow()[1] = 9;
        storage.expire("key", 2);
        assertArrayEquals(new byte[]{1, 2, 3}, storage.get("key").orElseThrow());
    }

    @Test
    void shouldKeepConcurrentExpiryAndPlainWritesConsistent() throws Exception {
        try (var workers = Executors.newFixedThreadPool(4)) {
            var tasks = java.util.stream.IntStream.range(0, 4).mapToObj(worker -> workers.submit(() -> {
                for (int i = 0; i < 500; i++) {
                    storage.set("shared", value, 1);
                    storage.expire("shared", 2);
                    storage.exists("shared");
                    storage.set("shared", value);
                }
            })).toList();
            for (var task : tasks) {
                task.get(10, TimeUnit.SECONDS);
            }
        }
        // Every worker ends with a persistent write, so the last write must clear TTL.
        clock.advanceMillis(3000);
        assertEquals(-1, storage.ttl("shared"));
        assertArrayEquals(value, storage.get("shared").orElseThrow());
    }
}
