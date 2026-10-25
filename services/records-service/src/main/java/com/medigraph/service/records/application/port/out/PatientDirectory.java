package com.medigraph.service.records.application.port.out;

import java.util.Set;

/** identity-mpi-service, seen from records: resolve any Health ID to its canonical identity; scrub demographics. */
public interface PatientDirectory {

    record ResolvedPatient(String healthId, Set<String> aliases) {
        public ResolvedPatient {
            aliases = Set.copyOf(aliases);
        }
    }

    ResolvedPatient resolve(String healthIdText);

    /** @return number of facility registrations whose demographics were scrubbed (idempotent) */
    int erase(String canonicalHealthId);
}
