package com.medigraph.ledger.chain;

public record VerificationResult(boolean valid, String reason) {
    public static VerificationResult ok() {
        return new VerificationResult(true, "ok");
    }

    public static VerificationResult fail(String reason) {
        return new VerificationResult(false, reason);
    }
}
