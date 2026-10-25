package com.medigraph.consent;

import com.medigraph.common.fhir.DataCategory;

import java.util.Objects;

/** "Can {@code requester} working at {@code facility} read {@code category} of {@code patientId} for {@code purpose}?" */
public record AccessRequest(String requester, String facility, String patientId, DataCategory category, Purpose purpose) {
    public AccessRequest {
        Objects.requireNonNull(requester);
        Objects.requireNonNull(patientId);
        Objects.requireNonNull(category);
        Objects.requireNonNull(purpose);
    }
}
