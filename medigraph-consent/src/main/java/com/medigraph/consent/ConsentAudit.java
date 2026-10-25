package com.medigraph.consent;

/** Side-channel for audit events; the app implements it by writing to the integrity ledger. */
public interface ConsentAudit {

    void consentGranted(ConsentArtefact artefact);

    void consentRevoked(ConsentArtefact artefact, String revokedBy);

    void breakGlass(BreakGlassGrant grant);

    void accessDecided(AccessRequest request, AccessDecision decision);

    ConsentAudit NOOP = new ConsentAudit() {
        public void consentGranted(ConsentArtefact a) { }
        public void consentRevoked(ConsentArtefact a, String by) { }
        public void breakGlass(BreakGlassGrant g) { }
        public void accessDecided(AccessRequest r, AccessDecision d) { }
    };
}
