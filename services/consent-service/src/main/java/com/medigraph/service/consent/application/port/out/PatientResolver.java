package com.medigraph.service.consent.application.port.out;

import java.util.Set;

/** Resolves any (possibly merged-away) Health ID to its canonical identity, owned by identity-mpi-service. */
public interface PatientResolver {

    record ResolvedPatient(String healthId, Set<String> aliases) {
        public ResolvedPatient {
            aliases = Set.copyOf(aliases);
        }
    }

    ResolvedPatient resolve(String healthIdText);
}
