package com.medigraph.common.security;

/**
 * Coarse platform roles carried in the access token's {@code roles} claim. Fine-grained, data-level decisions
 * (whose record, which category, which purpose) are made by consent policy, not by roles.
 * {@link #SERVICE} is for machine clients (client-credentials tokens) calling {@code /internal/**} APIs.
 */
public enum Role {
    DOCTOR, NURSE, RECEPTION, PATIENT, STEWARD, ADMIN, SERVICE;

    /** Spring Security authority name, e.g. {@code ROLE_DOCTOR}. */
    public String authority() {
        return "ROLE_" + name();
    }

    public boolean isClinician() {
        return this == DOCTOR || this == NURSE;
    }
}
