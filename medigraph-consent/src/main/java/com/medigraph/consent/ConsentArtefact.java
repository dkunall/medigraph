package com.medigraph.consent;

import com.medigraph.common.fhir.DataCategory;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A signed, purpose-bound permission: patient {@code patientId} lets {@code grantee} (a facility or clinician id)
 * read {@code categories} for {@code purpose} until {@code expiresAt}. The Ed25519 signature covers
 * {@link #signingPayload()}, so altering any field (e.g. widening categories in the database) invalidates it.
 */
public record ConsentArtefact(String consentId, String patientId, String grantee, Set<DataCategory> categories,
                              Purpose purpose, Instant issuedAt, Instant expiresAt, String signerKeyId,
                              String signature, Instant revokedAt) {

    public ConsentArtefact {
        Objects.requireNonNull(consentId);
        Objects.requireNonNull(patientId);
        Objects.requireNonNull(grantee);
        Objects.requireNonNull(purpose);
        Objects.requireNonNull(issuedAt);
        Objects.requireNonNull(expiresAt);
        if (categories == null || categories.isEmpty()) {
            throw new IllegalArgumentException("consent must name at least one data category");
        }
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("expiresAt must be after issuedAt");
        }
        categories = Set.copyOf(EnumSet.copyOf(categories));
    }

    /** Deterministic bytes covered by the signature (sorted categories; revocation is not signed, it is an event). */
    public byte[] signingPayload() {
        String cats = categories.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
        return String.join("|", "medigraph-consent-v1", consentId, patientId, grantee, cats, purpose.name(),
                Long.toString(issuedAt.toEpochMilli()), Long.toString(expiresAt.toEpochMilli()), signerKeyId)
                .getBytes(StandardCharsets.UTF_8);
    }

    public boolean isActiveAt(Instant now) {
        return revokedAt == null && !now.isBefore(issuedAt) && now.isBefore(expiresAt);
    }

    public ConsentArtefact withSignature(String keyId, String sig) {
        return new ConsentArtefact(consentId, patientId, grantee, categories, purpose, issuedAt, expiresAt, keyId, sig, revokedAt);
    }

    public ConsentArtefact revoked(Instant at) {
        return new ConsentArtefact(consentId, patientId, grantee, categories, purpose, issuedAt, expiresAt, signerKeyId, signature, at);
    }
}
