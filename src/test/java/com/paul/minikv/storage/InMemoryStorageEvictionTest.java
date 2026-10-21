package com.paul.minikv.storage;

import com.paul.minikv.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class InMemoryStorageEvictionTest {
    private final MutableClock clock = new MutableClock();
    private final byte[] value = {1, 2, 3};

    @Test
    void shouldRejectNonPositiveCapacityAndNullClock() {
        for (int capacity : new int[]{0, -1, Integer.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> new InMemoryStorage(capacity));
            assertThrows(IllegalArgumentException.class, () -> new InMemoryStorage(clock, capacity));
        }
        assertThrows(NullPointerException.class, () -> new InMemoryStorage(null, 2));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 16})
    void shouldEvictOldestKeyWhenAddingBeyondCapacity(int capacity) {
        StorageEngine storage = new InMemoryStorage(capacity);
        for (int i = 0; i < capacity; i++) {
            storage.set("key" + i, value);
        }
        assertEquals(capacity, storage.size());
        storage.set("new", value);
        assertEquals(capacity, storage.size());
        assertFalse(storage.exists("key0"));
        assertArrayEquals(value, storage.get("new").orElseThrow());
        for (int i = 1; i < capacity; i++) {
            assertTrue(storage.exists("key" + i));
        }
    }

    @Test
    void shouldRefreshRecencyOnSuccessfulGet() {
        StorageEngine storage = new InMemoryStorage(2);
        storage.set("a", value);
        storage.set("b", value);
        assertArrayEquals(value, storage.get("a").orElseThrow());
        storage.set("c", value);
        assertTrue(storage.exists("a"));
        assertFalse(storage.exists("b"));
        assertTrue(storage.exists("c"));
        assertEquals(2, storage.size());
    }

    @Test
    void shouldNotRefreshRecencyForMetadataOrMissingReads() {
        StorageEngine storage = new InMemoryStorage(clock, 2);
        storage.set("a", value);
        storage.set("b", value);
        assertTrue(storage.exists("a"));
        assertEquals(-1, storage.ttl("a"));
        assertTrue(storage.expire("a", 10));
        assertEquals(10, storage.ttl("a"));
        assertEquals(2, storage.size());
        assertTrue(storage.get("missing").isEmpty());
        assertFalse(storage.exists("missing"));
        assertEquals(-2, storage.ttl("missing"));
        assertFalse(storage.expire("missing", 10));
        assertFalse(storage.delete("missing"));
        storage.set("c", value);
        assertFalse(storage.exists("a"));
        assertTrue(storage.exists("b"));
        assertTrue(storage.exists("c"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldRefreshRecencyOnOverwriteWithoutEviction(boolean withExpiry) {
        StorageEngine storage = new InMemoryStorage(clock, 2);
        storage.set("a", value, 1);
        storage.set("b", value);
        byte[] replacement = {4, 5};
        if (withExpiry) {
            storage.set("a", replacement, 10);
        } else {
            storage.set("a", replacement);
        }
        replacement[0] = 9;
        assertTrue(storage.exists("b"));
        assertEquals(2, storage.size());
        assertEquals(withExpiry ? 10 : -1, storage.ttl("a"));
        storage.set("c", value);
        assertFalse(storage.exists("b"));
        clock.advanceMillis(1000);
        assertArrayEquals(new byte[]{4, 5}, storage.get("a").orElseThrow());
        assertTrue(storage.exists("c"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldReclaimExpiredNewestKeyBeforeEvictingLiveOldestKey(boolean withExpiry) {
        StorageEngine storage = new InMemoryStorage(clock, 2);
        storage.set("live", value);
        storage.set("expired", value, 1);
        clock.advanceMillis(1000);
        if (withExpiry) {
            storage.set("new", value, 10);
        } else {
            storage.set("new", value);
        }
        assertTrue(storage.exists("live"));
        assertTrue(storage.exists("new"));
        assertFalse(storage.exists("expired"));
        assertEquals(withExpiry ? 10 : -1, storage.ttl("new"));
        assertEquals(2, storage.size());
    }

    @Test
    void shouldCleanAllExpiredKeysAndKeepSurvivorOrder() {
        StorageEngine storage = new InMemoryStorage(clock, 4);
        storage.set("a", value);
        storage.set("expired1", value, 1);
        storage.set("b", value);
        storage.set("expired2", value, 1);
        clock.advanceMillis(1000);
        storage.set("c", value);
        storage.set("d", value);
        assertTrue(storage.exists("a"));
        assertTrue(storage.exists("b"));
        storage.set("e", value);
        assertFalse(storage.exists("a"));
        for (String key : new String[]{"b", "c", "d", "e"}) {
            assertTrue(storage.exists(key));
        }
        assertEquals(4, storage.size());
    }

    @Test
    void shouldReplaceExpiredKeyAtCapacityWithoutEvictingAnotherKey() {
        StorageEngine storage = new InMemoryStorage(clock, 2);
        storage.set("expired", value, 1);
        storage.set("live", value);
        clock.advanceMillis(1000);
        storage.set("expired", new byte[]{4}, 5);
        assertTrue(storage.exists("live"));
        assertEquals(5, storage.ttl("expired"));
        storage.set("new", value);
        assertFalse(storage.exists("live"));
        assertArrayEquals(new byte[]{4}, storage.get("expired").orElseThrow());
        assertTrue(storage.exists("new"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"delete", "expireZero", "expireNegative", "get", "exists", "ttl", "expire", "size"})
    void shouldReuseCapacityAfterEveryRemovalPath(String removal) {
        StorageEngine storage = new InMemoryStorage(clock, 2);
        storage.set("removed", value, 1);
        storage.set("survivor", value);
        switch (removal) {
            case "delete" -> assertTrue(storage.delete("removed"));
            case "expireZero" -> assertTrue(storage.expire("removed", 0));
            case "expireNegative" -> assertTrue(storage.expire("removed", -1));
            default -> {
                clock.advanceMillis(1000);
                switch (removal) {
                    case "get" -> assertTrue(storage.get("removed").isEmpty());
                    case "exists" -> assertFalse(storage.exists("removed"));
                    case "ttl" -> assertEquals(-2, storage.ttl("removed"));
                    case "expire" -> assertFalse(storage.expire("removed", 10));
                    case "size" -> assertEquals(1, storage.size());
                    default -> fail("unknown removal path");
                }
            }
        }
        storage.set("new", value);
        assertTrue(storage.exists("survivor"));
        assertTrue(storage.exists("new"));
        storage.set("removed", value);
        assertFalse(storage.exists("survivor"));
        assertTrue(storage.exists("new"));
        assertTrue(storage.exists("removed"));
        assertEquals(2, storage.size());
    }

    @Test
    void shouldNotMutateDataOrRecencyWhenWritesAreRejected() {
        StorageEngine storage = new InMemoryStorage(clock, 2);
        storage.set("a", value, 10);
        storage.set("b", value);
        assertThrows(IllegalArgumentException.class, () -> storage.set("a", null));
        assertThrows(IllegalArgumentException.class, () -> storage.set("new", null, 1));
        assertThrows(IllegalArgumentException.class, () -> storage.set(" ", value));
        for (String key : new String[]{"a", "new"}) {
            for (long seconds : new long[]{0, -1, Long.MAX_VALUE, Long.MAX_VALUE / 1000}) {
                assertThrows(IllegalArgumentException.class, () -> storage.set(key, new byte[]{9}, seconds));
            }
        }
        assertThrows(IllegalArgumentException.class, () -> storage.expire("a", Long.MAX_VALUE));
        assertEquals(10, storage.ttl("a"));
        assertEquals(2, storage.size());
        assertTrue(storage.exists("b"));
        assertFalse(storage.exists("new"));
        storage.set("c", value);
        assertFalse(storage.exists("a"), "failed writes must not protect a from eviction");
        assertArrayEquals(value, storage.get("b").orElseThrow());
        assertArrayEquals(value, storage.get("c").orElseThrow());
    }

    @Test
    void shouldKeepEvictedKeyAbsentForAllOperationsUntilExplicitlySet() {
        StorageEngine storage = new InMemoryStorage(clock, 1);
        storage.set("evicted", value, 10);
        storage.set("current", value, 20);
        assertTrue(storage.get("evicted").isEmpty());
        assertFalse(storage.exists("evicted"));
        assertEquals(-2, storage.ttl("evicted"));
        assertFalse(storage.expire("evicted", 100));
        assertFalse(storage.delete("evicted"));
        storage.set("evicted", new byte[]{4});
        assertEquals(-1, storage.ttl("evicted"));
        assertFalse(storage.exists("current"));
        assertEquals(1, storage.size());
    }

    @Test
    void shouldKeepCapacityAndStateConsistentDuringConcurrentAccess() throws Exception {
        int capacity = 8;
        int workerCount = 8;
        StorageEngine storage = new InMemoryStorage(clock, capacity);
        CountDownLatch ready = new CountDownLatch(workerCount);
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(workerCount)) {
            var tasks = IntStream.range(0, workerCount).mapToObj(worker -> workers.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                for (int i = 0; i < 300; i++) {
                    String key = "worker-" + worker + "-" + i;
                    storage.set(key, value, 1);
                    assertTrue(storage.size() <= capacity);
                    storage.get(key).ifPresent(bytes -> assertArrayEquals(value, bytes));
                    storage.expire(key, 2);
                    storage.exists(key);
                    storage.ttl(key);
                    storage.set("shared", value);
                    if (i % 3 == 0) {
                        storage.delete(key);
                    }
                    if (i % 10 == 0) {
                        clock.advanceMillis(1000);
                    }
                    assertTrue(storage.size() <= capacity);
                }
                return null;
            })).toList();
            try {
                assertTrue(ready.await(5, TimeUnit.SECONDS));
            } finally {
                start.countDown();
            }
            for (var task : tasks) {
                task.get(10, TimeUnit.SECONDS);
            }
        }
        for (int i = 0; i < capacity; i++) {
            storage.set("final-" + i, value);
        }
        assertEquals(capacity, storage.size());
        for (int i = 0; i < capacity; i++) {
            assertTrue(storage.exists("final-" + i));
        }
        storage.get("final-0");
        storage.set("extra", value);
        assertTrue(storage.exists("final-0"));
        assertFalse(storage.exists("final-1"));
        assertTrue(storage.exists("extra"));
        assertEquals(capacity, storage.size());
    }
}
