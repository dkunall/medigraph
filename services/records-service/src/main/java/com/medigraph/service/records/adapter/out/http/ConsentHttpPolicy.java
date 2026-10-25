package com.medigraph.service.records.adapter.out.http;

import com.medigraph.common.fhir.DataCategory;
import com.medigraph.common.security.Actor;
import com.medigraph.consent.Purpose;
import com.medigraph.platform.http.Downstream;
import com.medigraph.service.records.application.port.out.AccessPolicy;
import com.medigraph.service.records.domain.AccessVerdict;

import java.util.HashMap;
import java.util.Map;

/**
 * consent-service's decision endpoint. Fails closed: if consent-service is down the Downstream circuit breaker
 * raises UpstreamUnavailable (503) and nothing is decrypted.
 */
public final class ConsentHttpPolicy implements AccessPolicy {

    private record DecisionBody(boolean allowed, String basis, String reason) {
    }

    private final Downstream consent;

    public ConsentHttpPolicy(Downstream consent) {
        this.consent = consent;
    }

    @Override
    public AccessVerdict decide(Actor requester, String canonicalPatientId, DataCategory category, Purpose purpose) {
        Map<String, String> body = new HashMap<>();
        body.put("requester", requester.id());
        body.put("facility", requester.facility()); // null for patients
        body.put("patientId", canonicalPatientId);
        body.put("category", category.name());
        body.put("purpose", purpose.name());
        DecisionBody d = consent.post("/internal/access-decisions", body, DecisionBody.class, null);
        return new AccessVerdict(d.allowed(), d.basis(), d.reason());
    }
}
