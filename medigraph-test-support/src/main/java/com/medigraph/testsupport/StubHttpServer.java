package com.medigraph.testsupport;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Programmable stand-in for a downstream service: {@code on(path-prefix, path -> Reply)}. Longest matching prefix
 * wins. Unmatched paths get 404. Counts calls so tests can assert caching/retry behaviour.
 */
public final class StubHttpServer implements AutoCloseable {

    public record Reply(int status, String json) {
        public static Reply ok(String json) {
            return new Reply(200, json);
        }
    }

    /** What the stub received; {@code authorization} lets tests assert which token a service used. */
    public record Request(String method, String path, String query, String body, String authorization) {
    }

    private final HttpServer server;
    private final java.util.List<Request> received = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Map<String, Function<Request, Reply>> routes = new ConcurrentHashMap<>();
    private final AtomicInteger calls = new AtomicInteger();

    public StubHttpServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", ex -> {
            calls.incrementAndGet();
            String path = ex.getRequestURI().getPath();
            Request req = new Request(ex.getRequestMethod(), path, ex.getRequestURI().getRawQuery(),
                    new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                    ex.getRequestHeaders().getFirst("Authorization"));
            received.add(req);
            Reply r = routes.entrySet().stream().filter(e -> path.startsWith(e.getKey()))
                    .max(Map.Entry.comparingByKey((a, b) -> Integer.compare(a.length(), b.length())))
                    .map(e -> e.getValue().apply(req)).orElse(new Reply(404, "{\"title\":\"not found\"}"));
            byte[] body = r.json().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(r.status(), body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
    }

    public StubHttpServer on(String pathPrefix, Function<Request, Reply> handler) {
        routes.put(pathPrefix, handler);
        return this;
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public java.util.List<Request> received() {
        return java.util.List.copyOf(received);
    }

    public int calls() {
        return calls.get();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
