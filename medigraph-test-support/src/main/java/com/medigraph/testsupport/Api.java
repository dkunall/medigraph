package com.medigraph.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** Minimal real-HTTP JSON client for black-box service tests. */
public final class Api {

    public record Resp(int status, HttpResponse<String> raw, JsonNode json) {
        public String header(String name) {
            return raw.headers().firstValue(name).orElse(null);
        }

        @Override
        public String toString() {
            return status + " " + raw.body();
        }
    }

    public static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private final String base;

    public Api(String base) {
        this.base = base;
    }

    public static Api local(int port) {
        return new Api("http://localhost:" + port);
    }

    public Resp get(String path, String token) {
        return send("GET", path, null, token, Map.of());
    }

    public Resp post(String path, Object body, String token) {
        return send("POST", path, body, token, Map.of());
    }

    public Resp post(String path, Object body, String token, Map<String, String> headers) {
        return send("POST", path, body, token, headers);
    }

    public Resp delete(String path, String token) {
        return send("DELETE", path, null, token, Map.of());
    }

    public Resp send(String method, String path, Object body, String token, Map<String, String> headers) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20));
            if (token != null) {
                b.header("Authorization", "Bearer " + token);
            }
            headers.forEach(b::header);
            if (body != null) {
                b.header("Content-Type", "application/json");
                b.method(method, HttpRequest.BodyPublishers.ofString(
                        body instanceof String s ? s : JSON.writeValueAsString(body)));
            } else {
                b.method(method, HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode json = r.body() == null || r.body().isBlank() ? MissingNode.getInstance() : parse(r.body());
            return new Resp(r.statusCode(), r, json);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode parse(String body) {
        try {
            return JSON.readTree(body);
        } catch (IOException e) {
            return MissingNode.getInstance();
        }
    }
}
