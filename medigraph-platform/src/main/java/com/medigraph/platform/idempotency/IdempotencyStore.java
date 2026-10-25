package com.medigraph.platform.idempotency;

import java.util.Optional;

/** Port for remembering the outcome of a write request keyed by the client's Idempotency-Key. */
public interface IdempotencyStore {

    enum State { IN_PROGRESS, COMPLETED }

    record Entry(String requestHash, State state, int status, String contentType, byte[] body) {
    }

    /**
     * Atomically claims {@code scope} for this request. Returns empty when the claim succeeded (first request, or an
     * abandoned in-progress claim older than the lease was taken over), otherwise the existing entry.
     */
    Optional<Entry> claim(String scope, String requestHash);

    void complete(String scope, int status, String contentType, byte[] body);

    /** Forget a claim whose request failed with a server error, so the client may retry. */
    void release(String scope);
}
