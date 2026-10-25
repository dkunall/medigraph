package com.medigraph.platform.security.dev;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Optional;

/**
 * Tiny stand-alone dev issuer (JDK HttpServer) for tests that run a service without the gateway:
 * {@code GET /.well-known/jwks.json} and {@code POST /oauth2/token} (client credentials). Loopback only.
 */
public final class DevIssuerHttpServer implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpServer server;
    private final DevIssuer issuer;

    private DevIssuerHttpServer(HttpServer server, DevIssuer issuer) {
        this.server = server;
        this.issuer = issuer;
    }

    public static DevIssuerHttpServer start(DevIssuer issuer) {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            DevIssuerHttpServer d = new DevIssuerHttpServer(s, issuer);
            s.createContext("/.well-known/jwks.json", ex -> d.reply(ex, 200, issuer.minter().jwks()));
            s.createContext("/oauth2/token", ex -> {
                Optional<Map<String, Object>> t = issuer.clientCredentials(ex.getRequestHeaders().getFirst("Authorization"));
                ex.getRequestBody().readAllBytes();
                d.reply(ex, t.isPresent() ? 200 : 401, t.isPresent() ? t.get() : Map.of("error", "invalid_client"));
            });
            s.start();
            return d;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void reply(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public String jwksUri() {
        return baseUrl() + "/.well-known/jwks.json";
    }

    public String tokenUri() {
        return baseUrl() + "/oauth2/token";
    }

    public DevIssuer issuer() {
        return issuer;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
