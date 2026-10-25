package com.medigraph.common.error;

/** Authenticated but not allowed by policy (consent, ownership). Maps to 403. */
public class AccessDeniedException extends RuntimeException {
    public AccessDeniedException(String message) {
        super(message);
    }
}
