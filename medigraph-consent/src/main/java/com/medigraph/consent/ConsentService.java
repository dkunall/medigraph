package com.medigraph.consent;

import com.medigraph.common.crypto.Sha256;
import com.medigraph.common.fhir.DataCategory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Grant / revoke / enforce. The policy, in order:
 * <ol>
 *   <li>the patient reading their own data: allowed (SELF);</li>
 *   <li>an active, signature-valid consent whose grantee is the requester or their facility, covering the
 *       category and purpose: allowed (CONSENT);</li>
 *   <li>an active break-glass grant for this requester covering the category: allowed (BREAK_GLASS);</li>
 *   <li>otherwise denied. Every decision, including denials, goes to {@link ConsentAudit}.</li>
 * </ol>
 * A consent row whose signature no longer verifies (edited in the DB) is ignored, i.e. fails closed.
 */
public final class ConsentService {

    /** Break-glass sees what an emergency clinician needs to avoid harm, not the full record. */
    public static final Set<DataCategory> EMERGENCY_SCOPE =
            Set.copyOf(EnumSet.of(DataCategory.ALLERGIES, DataCategory.MEDICATIONS, DataCategory.CONDITIONS));
    public static final int MIN_REASON_LENGTH = 15;

    private final ConsentStore store;
    private final ConsentSigner signer;
    private final ConsentAudit audit;
    private final PatientNotifier notifier;
    private final Clock clock;
    private final Duration breakGlassWindow;
    private final Duration maxConsentDuration;

    public ConsentService(ConsentStore store, ConsentSigner signer, ConsentAudit audit, PatientNotifier notifier,
                          Clock clock, Duration breakGlassWindow, Duration maxConsentDuration) {
        this.store = store;
        this.signer = signer;
        this.audit = audit;
        this.notifier = notifier;
        this.clock = clock;
        this.breakGlassWindow = breakGlassWindow;
        this.maxConsentDuration = maxConsentDuration;
    }

    public ConsentArtefact grant(String patientId, String grantee, Set<DataCategory> categories, Purpose purpose,
                                 Duration validFor) {
        if (validFor.isNegative() || validFor.isZero() || validFor.compareTo(maxConsentDuration) > 0) {
            throw new ConsentException("consent duration must be between 0 and " + maxConsentDuration);
        }
        if (purpose == Purpose.EMERGENCY) {
            throw new ConsentException("EMERGENCY access is break-glass only, it cannot be pre-granted");
        }
        Instant now = clock.instant();
        ConsentArtefact unsigned = new ConsentArtefact(UUID.randomUUID().toString(), patientId, grantee, categories,
                purpose, now, now.plus(validFor), null, null, null);
        ConsentArtefact signed = signer.sign(unsigned);
        store.save(signed);
        audit.consentGranted(signed);
        return signed;
    }

    /** Only the patient (or their delegate, out of scope here) may revoke. Takes effect on the next read. */
    public ConsentArtefact revoke(String consentId, String actor) {
        ConsentArtefact c = store.find(consentId).orElseThrow(() -> new ConsentException("unknown consent " + consentId));
        if (!c.patientId().equals(actor)) {
            throw new ConsentException("only the patient can revoke this consent");
        }
        if (!store.revoke(consentId, clock.instant())) {
            throw new ConsentException("consent already revoked");
        }
        ConsentArtefact revoked = store.find(consentId).orElseThrow();
        audit.consentRevoked(revoked, actor);
        return revoked;
    }

    public AccessDecision evaluate(AccessRequest req) {
        AccessDecision d = decide(req);
        audit.accessDecided(req, d);
        return d;
    }

    private AccessDecision decide(AccessRequest req) {
        Instant now = clock.instant();
        if (req.requester().equals(req.patientId())) {
            return new AccessDecision(true, AccessDecision.Basis.SELF, null, "patient reading own record");
        }
        for (ConsentArtefact c : store.forPatient(req.patientId())) {
            boolean granteeMatches = c.grantee().equals(req.requester()) || c.grantee().equals(req.facility());
            if (granteeMatches && c.isActiveAt(now) && c.purpose() == req.purpose()
                    && c.categories().contains(req.category()) && signer.verify(c)) {
                return new AccessDecision(true, AccessDecision.Basis.CONSENT, c.consentId(), "covered by consent");
            }
        }
        for (BreakGlassGrant g : store.breakGlassFor(req.patientId())) {
            if (g.covers(req.requester(), req.category(), now)) {
                return new AccessDecision(true, AccessDecision.Basis.BREAK_GLASS, g.grantId(), "emergency break-glass");
            }
        }
        return AccessDecision.deny("no active consent covers " + req.category() + " for " + req.purpose());
    }

    /**
     * Emergency access without consent. Requires a substantive reason, grants only {@link #EMERGENCY_SCOPE} for a
     * short window, is written to the ledger (reason hash only), and notifies the patient.
     */
    public BreakGlassGrant breakGlass(String patientId, String requester, String facility, String reason) {
        if (reason == null || reason.strip().length() < MIN_REASON_LENGTH) {
            throw new ConsentException("break-glass requires a reason of at least " + MIN_REASON_LENGTH + " characters");
        }
        Instant now = clock.instant();
        BreakGlassGrant g = new BreakGlassGrant(UUID.randomUUID().toString(), patientId, requester, facility,
                reason.strip(), Sha256.hex(reason.strip()), EMERGENCY_SCOPE, now, now.plus(breakGlassWindow));
        store.saveBreakGlass(g);
        audit.breakGlass(g);
        notifier.notify(patientId, "BREAK_GLASS",
                "Emergency access to your records by " + requester + " at " + facility + " until " + g.expiresAt());
        return g;
    }

    public List<ConsentArtefact> consentsOf(String patientId) {
        return store.forPatient(patientId);
    }

    public Optional<ConsentArtefact> find(String consentId) {
        return store.find(consentId);
    }

    public boolean verifySignature(ConsentArtefact a) {
        return signer.verify(a);
    }
}
