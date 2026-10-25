package com.medigraph.common.error;

/** The request conflicts with current state (duplicate, lost race, illegal transition). Maps to 409. */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
