package com.medigraph.service.ledger.application;

/** Fail-stop: the replica could not persist an applied block and refuses all work until it is restarted. */
public class ReplicaFailedException extends RuntimeException {

    public ReplicaFailedException(String message) {
        super(message);
    }
}
