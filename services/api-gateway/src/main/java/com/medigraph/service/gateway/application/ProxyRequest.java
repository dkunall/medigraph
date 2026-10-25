package com.medigraph.service.gateway.application;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A request to relay. {@code headers} holds only the allow-listed headers (lower-case names); hop-by-hop and
 * spoofable headers never get this far.
 */
public record ProxyRequest(String method, String path, String rawQuery, Map<String, List<String>> headers, byte[] body) {

    public ProxyRequest {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");
        headers = Map.copyOf(headers);
        body = body == null ? new byte[0] : body;
    }

    public boolean idempotent() {
        return switch (method) {
            case "GET", "HEAD", "OPTIONS" -> true;
            default -> false;
        };
    }

    public String pathAndQuery() {
        return rawQuery == null || rawQuery.isEmpty() ? path : path + "?" + rawQuery;
    }
}
