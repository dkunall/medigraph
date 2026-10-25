package com.medigraph.ledger.chain;

import com.medigraph.common.crypto.Sha256;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * One ledger fact. The constructor is the "never medical data on chain" guard:
 * <ul>
 *   <li>{@code contentHash} must be a SHA-256 hex digest (or null for pure access events);</li>
 *   <li>{@code metadata} keys come from a fixed allow-list and values are short codes (no spaces, max 64 chars),
 *       so a free-text clinical note cannot be smuggled in. Free-text fields (e.g. a break-glass reason) are stored
 *       off-chain and only their hash goes here.</li>
 * </ul>
 * {@link #canonicalBytes()} is a deterministic encoding (sorted metadata) used as the Merkle leaf preimage.
 */
public record LedgerEntry(String entryId, EntryType type, String subject, String actor, String contentHash,
                          long timestampMillis, Map<String, String> metadata) {

    public static final Set<String> ALLOWED_METADATA = Set.of(
            "recordId", "resourceType", "consentId", "purpose", "categories", "facility", "decision",
            "reasonHash", "drug", "severity", "expiresAt", "keyVersion", "requester");

    private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_.:,/@+\\-]{1,64}");

    public LedgerEntry {
        Objects.requireNonNull(entryId, "entryId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(actor, "actor");
        if (contentHash != null && !HEX64.matcher(contentHash).matches()) {
            throw new IllegalArgumentException("contentHash must be lowercase SHA-256 hex");
        }
        if (type == EntryType.RECORD_HASH && contentHash == null) {
            throw new IllegalArgumentException("RECORD_HASH entries need a contentHash");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        for (Map.Entry<String, String> e : metadata.entrySet()) {
            if (!ALLOWED_METADATA.contains(e.getKey())) {
                throw new IllegalArgumentException("metadata key not allowed on ledger: " + e.getKey());
            }
            if (e.getValue() == null || !CODE.matcher(e.getValue()).matches()) {
                throw new IllegalArgumentException("metadata value for '" + e.getKey()
                        + "' must be a short code (no free text on ledger)");
            }
        }
    }

    public byte[] canonicalBytes() {
        StringBuilder sb = new StringBuilder(256);
        sb.append(entryId).append('|').append(type).append('|').append(subject).append('|').append(actor).append('|')
                .append(contentHash == null ? "" : contentHash).append('|').append(timestampMillis);
        new TreeMap<>(metadata).forEach((k, v) -> sb.append('|').append(k).append('=').append(v));
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    public String fingerprint() {
        return Sha256.hex(canonicalBytes());
    }
}
