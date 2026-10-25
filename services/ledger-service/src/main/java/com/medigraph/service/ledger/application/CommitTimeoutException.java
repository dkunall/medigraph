package com.medigraph.service.ledger.application;

/** The proposal was not committed in time (no quorum reachable). It may still commit later; retries are deduplicated. */
public class CommitTimeoutException extends RuntimeException {

    public CommitTimeoutException(String message) {
        super(message);
    }
}
