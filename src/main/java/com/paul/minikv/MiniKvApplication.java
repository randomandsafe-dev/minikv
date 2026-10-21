package com.paul.minikv;

import com.paul.minikv.storage.InMemoryStorage;
import com.paul.minikv.server.MiniKvServer;

public final class MiniKvApplication {

    private MiniKvApplication() {
    }

    public static void main(String[] args) throws InterruptedException {
        if (args.length > 3) {
            throw new IllegalArgumentException("Usage: java -jar minikv.jar [port] [host] [maxEntries]");
        }
        int port = args.length >= 1 ? Integer.parseInt(args[0]) : 6379;
        String host = args.length >= 2 ? args[1] : "127.0.0.1";
        int maxEntries = args.length >= 3 ? Integer.parseInt(args[2]) : Integer.MAX_VALUE;
        try (MiniKvServer server = new MiniKvServer(host, port, new InMemoryStorage(maxEntries))) {
            Thread shutdownHook = new Thread(server::close, "minikv-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            try {
                server.start();
                System.out.println("MiniKV listening on " + host + ":" + server.port());
                System.out.println("LRU maxEntries: " + maxEntries);
                server.awaitClose();
            } finally {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (IllegalStateException ignored) {
                    // JVM shutdown is already running the hook.
                }
            }
        }
    }
}
