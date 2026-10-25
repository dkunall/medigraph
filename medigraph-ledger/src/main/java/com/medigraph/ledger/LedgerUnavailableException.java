package com.medigraph.ledger;

public class LedgerUnavailableException extends RuntimeException {
    public LedgerUnavailableException(String message) {
        super(message);
    }
}
