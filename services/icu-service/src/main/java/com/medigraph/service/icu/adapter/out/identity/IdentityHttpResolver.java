package com.medigraph.service.icu.adapter.out.identity;

import com.medigraph.platform.http.Downstream;
import com.medigraph.service.icu.application.port.out.PatientResolver;

/** identity-mpi-service's internal API, called with this service's own token (SERVICE role). */
public final class IdentityHttpResolver implements PatientResolver {

    private record IdentityBody(String healthId) {
    }

    private final Downstream identity;

    public IdentityHttpResolver(Downstream identity) {
        this.identity = identity;
    }

    @Override
    public String canonical(String healthId) {
        return identity.get("/internal/patients/{id}", IdentityBody.class, healthId).healthId();
    }
}
