package com.paul.minikv;

import com.paul.minikv.storage.InMemoryStorage;
import com.paul.minikv.storage.StorageEngine;

import java.nio.charset.StandardCharsets;

public final class MiniKvApplication {

    private MiniKvApplication() {
    }

    public static void main(String[] args) {
        StorageEngine storage = new InMemoryStorage();

        storage.set(
                "name",
                "MiniKV".getBytes(StandardCharsets.UTF_8)
        );

        String value = storage.get("name")
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                .orElse("(nil)");

        System.out.println("name = " + value);
    }
}