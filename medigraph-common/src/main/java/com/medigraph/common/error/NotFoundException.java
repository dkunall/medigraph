package com.medigraph.common.error;

/** The addressed resource does not exist (or the caller may not learn that it exists). Maps to 404. */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
