package com.medigraph.service.gateway.application;

import java.util.List;
import java.util.Map;

/** What the backend answered (allow-listed response headers only). */
public record ProxyResponse(int status, Map<String, List<String>> headers, byte[] body) {

    public ProxyResponse {
        headers = Map.copyOf(headers);
        body = body == null ? new byte[0] : body;
    }
}
