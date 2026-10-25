package com.medigraph.service.gateway.adapter.in.web;

import com.medigraph.platform.web.CorrelationIdFilter;
import com.medigraph.service.gateway.application.GatewayProxy;
import com.medigraph.service.gateway.application.ProxyRequest;
import com.medigraph.service.gateway.application.ProxyResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Edge entry point for {@code /api/**}. The JWT was already validated by the gateway's resource-server filter
 * (fail fast at the edge); the same bearer token is relayed so each service re-validates it and applies its own
 * {@code @PreAuthorize} rules (zero trust between hops). Only allow-listed headers are forwarded, and
 * {@code X-Forwarded-For} is set from the socket peer rather than appended to a client-supplied value.
 */
@RestController
public class ProxyController {

    private static final Set<String> REQUEST_HEADERS = Set.of("authorization", "content-type", "accept",
            "accept-language", "idempotency-key", "traceparent", "tracestate");

    private final GatewayProxy proxy;
    private final int maxBodyBytes;

    public ProxyController(GatewayProxy proxy,
                           @Value("${medigraph.gateway.max-body-bytes:1048576}") int maxBodyBytes) {
        this.proxy = proxy;
        this.maxBodyBytes = maxBodyBytes;
    }

    @RequestMapping("/api/**")
    public ResponseEntity<byte[]> relay(HttpServletRequest req) throws IOException {
        Map<String, List<String>> headers = new HashMap<>();
        for (String name : Collections.list(req.getHeaderNames())) {
            String key = name.toLowerCase(Locale.ROOT);
            if (REQUEST_HEADERS.contains(key)) {
                headers.put(key, Collections.list(req.getHeaders(name)));
            }
        }
        String cid = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (cid != null) {
            headers.put(CorrelationIdFilter.HEADER.toLowerCase(Locale.ROOT), List.of(cid));
        }
        headers.put("x-forwarded-for", List.of(req.getRemoteAddr()));
        String path = req.getRequestURI().substring(req.getContextPath().length());
        ProxyResponse res = proxy.handle(new ProxyRequest(req.getMethod(), path, req.getQueryString(), headers,
                readBounded(req)));
        HttpHeaders out = new HttpHeaders();
        res.headers().forEach(out::put);
        return ResponseEntity.status(res.status()).headers(out).body(res.body());
    }

    private byte[] readBounded(HttpServletRequest req) throws IOException {
        if (req.getContentLengthLong() > maxBodyBytes) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "request body too large");
        }
        try (InputStream in = req.getInputStream()) {
            byte[] body = in.readNBytes(maxBodyBytes + 1);
            if (body.length > maxBodyBytes) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "request body too large");
            }
            return body;
        }
    }
}
