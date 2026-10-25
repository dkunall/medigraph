package com.medigraph.common.error;

/** A dependency (another service, the ledger quorum, a KMS) could not be reached in time. Maps to 503. */
public class UpstreamUnavailableException extends RuntimeException {
    public UpstreamUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public UpstreamUnavailableException(String message) {
        super(message);
    }
}
