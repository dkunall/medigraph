package com.medigraph.service.consent.domain;

import com.medigraph.common.crypto.Sha256;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Identifier of a consent-signing key, derived from the public key itself (first 64 bits of SHA-256 over the X.509
 * encoding). The same PEM therefore always yields the same id across restarts and replicas, and two different keys
 * cannot silently share an id.
 */
public record SigningKeyId(String value) {

    private static final Pattern FORMAT = Pattern.compile("consent-ed25519-[0-9a-f]{16}");

    public SigningKeyId {
        Objects.requireNonNull(value, "value");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("malformed signing key id: " + value);
        }
    }

    public static SigningKeyId of(byte[] x509PublicKey) {
        return new SigningKeyId("consent-ed25519-" + Sha256.hex(x509PublicKey).substring(0, 16));
    }

    @Override
    public String toString() {
        return value;
    }
}
