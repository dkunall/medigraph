package com.medigraph.platform.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.medigraph.common.error.NotFoundException;
import com.medigraph.common.error.UpstreamUnavailableException;
import com.medigraph.platform.http.Downstream;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Client for the replicated ledger-service. Writes and reads must reach the Raft leader; any follower answers 421 Misdirected Request
 * (with an {@code X-Raft-Leader} hint), so the client tries the last known-good node first and then the others.
 * Each node has its own circuit breaker, so a dead replica is skipped quickly. Submissions are idempotent by
 * entry id on the ledger side, which makes the at-least-once outbox relay safe.
 */
public final class LedgerClient {

    private final List<Downstream> nodes;
    private final AtomicInteger preferred = new AtomicInteger();

    public LedgerClient(List<Downstream> nodes) {
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("no ledger nodes configured");
        }
        this.nodes = List.copyOf(nodes);
    }

    public record Verification(boolean valid, String reason) {
    }

    /** Submits ledger entries (JSON objects as produced by the services) and waits until they are committed. */
    public void submit(List<JsonNode> entries, String idempotencyKey) {
        onLeader(n -> n.post("/internal/ledger/entries", entries, JsonNode.class, idempotencyKey));
    }

    public Verification verifyContentHash(String entryId, String contentHash) {
        return onLeader(n -> n.get("/internal/ledger/entries/{id}/verification?contentHash={h}", Verification.class,
                entryId, contentHash));
    }

    public JsonNode proof(String entryId) {
        return onLeader(n -> n.get("/internal/ledger/entries/{id}/proof", JsonNode.class, entryId));
    }

    public JsonNode status() {
        return onLeader(n -> n.get("/internal/ledger/status", JsonNode.class));
    }

    private <T> T onLeader(java.util.function.Function<Downstream, T> call) {
        List<RuntimeException> failures = new ArrayList<>();
        int start = preferred.get();
        for (int i = 0; i < nodes.size(); i++) {
            int idx = (start + i) % nodes.size();
            try {
                T result = call.apply(nodes.get(idx));
                preferred.set(idx);
                return result;
            } catch (NotFoundException e) {
                throw e;
            } catch (UpstreamUnavailableException e) {
                failures.add(e);
            }
        }
        throw new UpstreamUnavailableException("no ledger leader reachable (" + failures.size() + " nodes tried)");
    }
}
