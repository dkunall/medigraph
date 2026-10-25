package com.medigraph.consent;

public record AccessDecision(boolean allowed, Basis basis, String reference, String reason) {

    public enum Basis { SELF, CONSENT, BREAK_GLASS, DENIED }

    public static AccessDecision deny(String reason) {
        return new AccessDecision(false, Basis.DENIED, null, reason);
    }
}
