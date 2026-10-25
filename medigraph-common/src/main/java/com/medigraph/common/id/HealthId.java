package com.medigraph.common.id;

import java.security.SecureRandom;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * Universal Health ID: 13 random digits + 1 Verhoeff check digit = 14 digits, displayed as {@code XXXX-XXXX-XXXX-XX}.
 *
 * <p>The canonical (stored) form is the 14 bare digits. {@link #parse(String)} accepts either form, so the check
 * digit catches a mistyped digit or two swapped neighbours at the front desk before any lookup happens.
 */
public final class HealthId implements Comparable<HealthId> {

    public static final int PAYLOAD_DIGITS = 13;
    public static final int TOTAL_DIGITS = PAYLOAD_DIGITS + 1;

    private final String digits;

    private HealthId(String digits) {
        this.digits = digits;
    }

    /** Generates a new random Health ID using a {@link SecureRandom} (IDs must not be guessable/enumerable). */
    public static HealthId generate() {
        return generate(Holder.RANDOM);
    }

    public static HealthId generate(RandomGenerator random) {
        StringBuilder sb = new StringBuilder(TOTAL_DIGITS);
        sb.append(1 + random.nextInt(9)); // no leading zero: keeps the ID 14 digits when treated as a number
        for (int i = 1; i < PAYLOAD_DIGITS; i++) {
            sb.append(random.nextInt(10));
        }
        sb.append(Verhoeff.checkDigit(sb.toString()));
        return new HealthId(sb.toString());
    }

    /** Builds an ID from 13 payload digits by computing the check digit. */
    public static HealthId fromPayload(String thirteenDigits) {
        if (thirteenDigits == null || thirteenDigits.length() != PAYLOAD_DIGITS) {
            throw new IllegalArgumentException("payload must be exactly 13 digits");
        }
        return new HealthId(thirteenDigits + Verhoeff.checkDigit(thirteenDigits));
    }

    /**
     * Parses {@code XXXX-XXXX-XXXX-XX} or 14 bare digits (spaces are tolerated).
     *
     * @throws InvalidHealthIdException if the shape is wrong or the Verhoeff check fails
     */
    public static HealthId parse(String text) {
        if (text == null) {
            throw new InvalidHealthIdException("Health ID is required");
        }
        String bare = text.replace("-", "").replace(" ", "");
        if (bare.length() != TOTAL_DIGITS || !bare.chars().allMatch(Character::isDigit)) {
            throw new InvalidHealthIdException("Health ID must have 14 digits (XXXX-XXXX-XXXX-XX)");
        }
        if (!Verhoeff.isValid(bare)) {
            throw new InvalidHealthIdException("Health ID check digit mismatch (typo?)");
        }
        return new HealthId(bare);
    }

    public static boolean isValid(String text) {
        try {
            parse(text);
            return true;
        } catch (InvalidHealthIdException e) {
            return false;
        }
    }

    public String digits() {
        return digits;
    }

    public String formatted() {
        return digits.substring(0, 4) + '-' + digits.substring(4, 8) + '-' + digits.substring(8, 12) + '-' + digits.substring(12);
    }

    @Override
    public String toString() {
        return formatted();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof HealthId other && digits.equals(other.digits);
    }

    @Override
    public int hashCode() {
        return Objects.hash(digits);
    }

    @Override
    public int compareTo(HealthId o) {
        return digits.compareTo(o.digits);
    }

    private static final class Holder {
        static final SecureRandom RANDOM = new SecureRandom();
    }
}
