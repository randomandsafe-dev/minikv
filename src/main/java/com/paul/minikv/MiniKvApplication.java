package com.paul.minikv;

import com.paul.minikv.storage.InMemoryStorage;
import com.paul.minikv.server.MiniKvServer;

public final class MiniKvApplication {

    private MiniKvApplication() {
    }

    public static void main(String[] args) throws InterruptedException {
        if (args.length > 2) {
            throw new IllegalArgumentException("Usage: java -jar minikv.jar [port] [host]");
        }
        int port = args.length >= 1 ? Integer.parseInt(args[0]) : 6379;
        String host = args.length >= 2 ? args[1] : "127.0.0.1";
        try (MiniKvServer server = new MiniKvServer(host, port, new InMemoryStorage())) {
            Thread shutdownHook = new Thread(server::close, "minikv-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            try {
                server.start();
                System.out.println("MiniKV listening on " + host + ":" + server.port());
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
