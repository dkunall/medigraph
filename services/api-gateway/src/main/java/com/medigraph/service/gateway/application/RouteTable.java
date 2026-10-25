package com.medigraph.service.gateway.application;

import com.medigraph.common.error.NotFoundException;
import com.medigraph.service.gateway.domain.PathTrie;
import com.medigraph.service.gateway.domain.RouteTarget;

import java.util.Map;

/** Path → service resolution over a {@link PathTrie} (most specific pattern wins). Immutable after construction. */
public final class RouteTable {

    private final PathTrie<RouteTarget> trie = new PathTrie<>();

    /** @param routes pattern → service name, e.g. {@code "/api/v1/patients/{@literal *}/consents" -> "consent"} */
    public RouteTable(Map<String, String> routes) {
        if (routes.isEmpty()) {
            throw new IllegalArgumentException("no routes configured");
        }
        routes.forEach((pattern, service) -> trie.insert(pattern, new RouteTarget(service)));
    }

    public RouteTarget resolve(String path) {
        if (path.contains("/../") || path.endsWith("/..") || path.contains("/./")) {
            throw new IllegalArgumentException("path traversal segments are not allowed");
        }
        return trie.match(path).orElseThrow(() -> new NotFoundException("no route for " + path));
    }

    public int size() {
        return trie.size();
    }
}
