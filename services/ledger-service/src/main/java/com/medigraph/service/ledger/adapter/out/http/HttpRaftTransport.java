package com.medigraph.service.ledger.adapter.out.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medigraph.ledger.raft.RaftMessage;
import com.medigraph.platform.security.ServiceTokenProvider;
import com.medigraph.service.ledger.adapter.wire.RaftWire;
import com.medigraph.service.ledger.application.port.out.RaftTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Raft over HTTP: one single-threaded sender per peer with a bounded queue that drops the oldest message when a
 * peer is slow or down (Raft re-sends: heartbeats repeat, elections retry), so a dead peer never blocks the leader.
 * Calls carry this node's client-credentials token; peers accept them only with the SERVICE role.
 * No retry/circuit breaker here on purpose: Raft's own timers are the retry policy.
 */
public final class HttpRaftTransport implements RaftTransport, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HttpRaftTransport.class);

    private final Map<String, URI> peers;
    private final Map<String, ThreadPoolExecutor> senders = new HashMap<>();
    private final HttpClient http;
    private final ObjectMapper json;
    private final ServiceTokenProvider tokens;
    private final Duration timeout;

    public HttpRaftTransport(Map<String, String> peerBaseUrls, ObjectMapper json, ServiceTokenProvider tokens, Duration timeout) {
        this.peers = new HashMap<>();
        peerBaseUrls.forEach((id, url) -> peers.put(id, URI.create(url.replaceAll("/$", "") + "/internal/raft/messages")));
        this.json = json;
        this.tokens = tokens;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).version(HttpClient.Version.HTTP_1_1).build();
        for (String id : peers.keySet()) {
            ThreadPoolExecutor ex = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(256),
                    r -> {
                        Thread t = new Thread(r, "raft-send-" + id);
                        t.setDaemon(true);
                        return t;
                    }, new ThreadPoolExecutor.DiscardOldestPolicy());
            senders.put(id, ex);
        }
    }

    @Override
    public void send(RaftMessage message) {
        ThreadPoolExecutor ex = senders.get(message.to());
        URI uri = peers.get(message.to());
        if (ex == null || ex.isShutdown()) {
            return;
        }
        RaftWire wire = RaftWire.of(message);
        ex.execute(() -> deliver(uri, wire));
    }

    private void deliver(URI uri, RaftWire wire) {
        try {
            HttpRequest req = HttpRequest.newBuilder(uri).timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + tokens.token())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(wire))).build();
            HttpResponse<Void> res = http.send(req, HttpResponse.BodyHandlers.discarding());
            if (res.statusCode() == 401) {
                // the peer no longer accepts our cached token (issuer key rotated): fetch a new one for the next send
                tokens.invalidate();
                log.warn("raft {} to {} rejected our token; renewing it", wire.type(), wire.to());
            } else if (res.statusCode() >= 300) {
                log.debug("raft {} to {} answered {}", wire.type(), wire.to(), res.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("raft {} to {} failed: {}", wire.type(), wire.to(), e.toString());
        }
    }

    @Override
    public void close() {
        senders.values().forEach(ThreadPoolExecutor::shutdownNow);
    }
}
