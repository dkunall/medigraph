package com.medigraph.service.records.application.port.out;

import com.medigraph.common.fhir.DataCategory;
import com.medigraph.common.security.Actor;
import com.medigraph.consent.Purpose;
import com.medigraph.service.records.domain.AccessVerdict;

/** consent-service's decision point. Every call is audited (ACCESS / ACCESS_DENIED) by consent-service. */
public interface AccessPolicy {

    AccessVerdict decide(Actor requester, String canonicalPatientId, DataCategory category, Purpose purpose);
}
