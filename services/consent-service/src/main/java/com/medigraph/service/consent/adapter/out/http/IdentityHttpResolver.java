package com.medigraph.service.consent.adapter.out.http;

import com.medigraph.platform.http.Downstream;
import com.medigraph.service.consent.application.port.out.PatientResolver;

import java.util.Set;

/** Calls identity-mpi-service as this service (SERVICE role). Unknown IDs surface as NotFoundException (404). */
public final class IdentityHttpResolver implements PatientResolver {

    private record IdentityBody(String healthId, Set<String> aliases) {
    }

    private final Downstream identity;

    public IdentityHttpResolver(Downstream identity) {
        this.identity = identity;
    }

    @Override
    public ResolvedPatient resolve(String healthIdText) {
        IdentityBody b = identity.get("/internal/patients/{id}", IdentityBody.class, healthIdText);
        return new ResolvedPatient(b.healthId(), b.aliases());
    }
}
