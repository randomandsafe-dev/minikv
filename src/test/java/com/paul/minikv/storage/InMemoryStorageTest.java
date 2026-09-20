package com.paul.minikv.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryStorageTest {

    private StorageEngine storage;

    @BeforeEach
    void setUp() {
        storage = new InMemoryStorage();
    }

    @Test
    void shouldSetAndGetValue() {
        storage.set("name", bytes("Alice"));

        assertArrayEquals(
                bytes("Alice"),
                storage.get("name").orElseThrow()
        );
    }

    @Test
    void shouldReturnEmptyWhenKeyDoesNotExist() {
        assertTrue(storage.get("missing").isEmpty());
    }

    @Test
    void shouldOverwriteExistingValue() {
        storage.set("name", bytes("Alice"));
        storage.set("name", bytes("Bob"));

        assertArrayEquals(
                bytes("Bob"),
                storage.get("name").orElseThrow()
        );
    }

    @Test
    void shouldDeleteExistingKey() {
        storage.set("name", bytes("Alice"));

        assertTrue(storage.delete("name"));
        assertFalse(storage.exists("name"));
    }

    @Test
    void shouldReturnFalseWhenDeletingMissingKey() {
        assertFalse(storage.delete("missing"));
    }

    @Test
    void shouldReportWhetherKeyExists() {
        storage.set("name", bytes("Alice"));

        assertTrue(storage.exists("name"));
        assertFalse(storage.exists("missing"));
    }

    @Test
    void shouldRejectBlankKey() {
        assertThrows(
                IllegalArgumentException.class,
                () -> storage.set(" ", bytes("Alice"))
        );
    }

    @Test
    void shouldRejectNullValue() {
        assertThrows(
                IllegalArgumentException.class,
                () -> storage.set("name", null)
        );
    }

    @Test
    void shouldMakeDefensiveCopyWhenSettingValue() {
        byte[] original = bytes("Alice");

        storage.set("name", original);
        original[0] = 'X';

        assertArrayEquals(
                bytes("Alice"),
                storage.get("name").orElseThrow()
        );
    }

    @Test
    void shouldMakeDefensiveCopyWhenGettingValue() {
        storage.set("name", bytes("Alice"));

        byte[] returned = storage.get("name").orElseThrow();
        returned[0] = 'X';

        assertArrayEquals(
                bytes("Alice"),
                storage.get("name").orElseThrow()
        );
    }

    @Test
    void shouldReturnNumberOfEntries() {
        storage.set("key1", bytes("value1"));
        storage.set("key2", bytes("value2"));

        assertEquals(2, storage.size());
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}