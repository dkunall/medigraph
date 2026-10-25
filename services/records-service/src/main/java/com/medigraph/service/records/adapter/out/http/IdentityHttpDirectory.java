package com.medigraph.service.records.adapter.out.http;

import com.medigraph.platform.http.Downstream;
import com.medigraph.service.records.application.port.out.PatientDirectory;

import java.util.Map;
import java.util.Set;

/** identity-mpi-service's internal API, called with this service's own token (SERVICE role). */
public final class IdentityHttpDirectory implements PatientDirectory {

    private record IdentityBody(String healthId, Set<String> aliases) {
    }

    private record ErasureBody(String healthId, int registrationsScrubbed) {
    }

    private final Downstream identity;

    public IdentityHttpDirectory(Downstream identity) {
        this.identity = identity;
    }

    @Override
    public ResolvedPatient resolve(String healthIdText) {
        IdentityBody b = identity.get("/internal/patients/{id}", IdentityBody.class, healthIdText);
        return new ResolvedPatient(b.healthId(), b.aliases());
    }

    @Override
    public int erase(String canonicalHealthId) {
        // deterministic idempotency key: a retried erasure is replayed, not re-executed
        return identity.post("/internal/patients/{id}/erasure", Map.of(), ErasureBody.class,
                "erase-" + canonicalHealthId, canonicalHealthId).registrationsScrubbed();
    }
}
