package com.medigraph.service.gateway;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** Fake backend service that records every request (all headers) and answers with a fixed status. */
final class RecordingBackend implements AutoCloseable {

    record Seen(String method, String pathAndQuery, Map<String, List<String>> headers, String body) {
        String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> e.getValue().get(0)).findFirst().orElse(null);
        }
    }

    private final HttpServer server;
    final List<Seen> seen = new CopyOnWriteArrayList<>();
    volatile int status = 200;

    RecordingBackend(String name) {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String uri = ex.getRequestURI().getRawPath()
                    + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery());
            seen.add(new Seen(ex.getRequestMethod(), uri, Map.copyOf(ex.getRequestHeaders()), body));
            byte[] out = ("{\"service\":\"" + name + "\"}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.getResponseHeaders().add("Location", "/api/v1/x/1");
            ex.getResponseHeaders().add("Set-Cookie", "leak=1");
            ex.getResponseHeaders().add("Idempotent-Replayed", "true");
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
