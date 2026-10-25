package com.medigraph.service.consent.application;

import com.medigraph.common.crypto.Sha256;
import com.medigraph.common.fhir.DataCategory;
import com.medigraph.common.port.UnitOfWork;
import com.medigraph.consent.AccessDecision;
import com.medigraph.consent.AccessRequest;
import com.medigraph.consent.BreakGlassGrant;
import com.medigraph.consent.ConsentArtefact;
import com.medigraph.consent.ConsentAudit;
import com.medigraph.ledger.chain.EntryType;
import com.medigraph.ledger.chain.LedgerEntry;
import com.medigraph.service.consent.application.port.out.LedgerAnchor;

import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Anchors consent events and access decisions on the ledger through the transactional outbox. Only identifiers,
 * codes and hashes go on chain (the signature and the break-glass reason are hashed). Each call joins the caller's
 * transaction when there is one (grant/revoke/break-glass are atomic with their row) and opens its own otherwise
 * (an access decision is a read whose audit row must still be durable).
 */
public final class OutboxConsentAudit implements ConsentAudit {

    private final LedgerAnchor ledger;
    private final UnitOfWork tx;
    private final Clock clock;

    public OutboxConsentAudit(LedgerAnchor ledger, UnitOfWork tx, Clock clock) {
        this.ledger = ledger;
        this.tx = tx;
        this.clock = clock;
    }

    private static String cats(Set<DataCategory> c) {
        return c.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    private void anchor(LedgerEntry e) {
        tx.run(() -> ledger.anchor(e));
    }

    @Override
    public void consentGranted(ConsentArtefact a) {
        anchor(new LedgerEntry("consent:" + a.consentId(), EntryType.CONSENT_GRANTED, a.patientId(), a.patientId(),
                Sha256.hex(a.signature()), clock.millis(), Map.of("consentId", a.consentId(), "purpose", a.purpose().name(),
                "categories", cats(a.categories()), "requester", a.grantee(),
                "expiresAt", String.valueOf(a.expiresAt().getEpochSecond()))));
    }

    @Override
    public void consentRevoked(ConsentArtefact a, String revokedBy) {
        anchor(new LedgerEntry("revoke:" + a.consentId(), EntryType.CONSENT_REVOKED, a.patientId(), revokedBy, null,
                clock.millis(), Map.of("consentId", a.consentId())));
    }

    @Override
    public void breakGlass(BreakGlassGrant g) {
        anchor(new LedgerEntry("breakglass:" + g.grantId(), EntryType.BREAK_GLASS, g.patientId(), g.requester(),
                g.reasonHash(), clock.millis(), Map.of("facility", g.facility(), "reasonHash", g.reasonHash(),
                "categories", cats(g.scope()), "expiresAt", String.valueOf(g.expiresAt().getEpochSecond()))));
    }

    @Override
    public void accessDecided(AccessRequest r, AccessDecision d) {
        anchor(new LedgerEntry("access:" + UUID.randomUUID(), d.allowed() ? EntryType.ACCESS : EntryType.ACCESS_DENIED,
                r.patientId(), r.requester(), null, clock.millis(), Map.of("facility", r.facility() == null ? "none" : r.facility(),
                "categories", r.category().name(), "purpose", r.purpose().name(), "decision", d.basis().name())));
    }
}
