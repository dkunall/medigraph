package com.medigraph.service.consent.application;

import com.medigraph.consent.AccessDecision;
import com.medigraph.consent.AccessRequest;
import com.medigraph.consent.ConsentService;
import com.medigraph.service.consent.application.port.out.PatientResolver;

/**
 * The policy decision point other services call before releasing clinical data. The patient is canonicalised first
 * so a request made under a merged-away Health ID is judged (and audited) against the surviving identity.
 */
public final class AccessDecisions {

    private final ConsentService consent;
    private final PatientResolver patients;

    public AccessDecisions(ConsentService consent, PatientResolver patients) {
        this.consent = consent;
        this.patients = patients;
    }

    public AccessDecision decide(AccessRequest r) {
        String canonical = patients.resolve(r.patientId()).healthId();
        return consent.evaluate(new AccessRequest(r.requester(), r.facility(), canonical, r.category(), r.purpose()));
    }
}
