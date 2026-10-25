package com.medigraph.service.identity.application;

import com.medigraph.common.error.AccessDeniedException;
import com.medigraph.common.error.NotFoundException;
import com.medigraph.common.id.HealthId;
import com.medigraph.common.port.Outbox;
import com.medigraph.common.port.UnitOfWork;
import com.medigraph.common.security.Actor;
import com.medigraph.common.security.Role;
import com.medigraph.mpi.MasterPatientIndex;
import com.medigraph.service.identity.application.port.out.PatientDirectory;
import com.medigraph.service.identity.application.port.out.PatientDirectory.RegistrationRow;
import com.medigraph.service.identity.domain.HealthIdentity;

import java.util.HashSet;
import java.util.List;

/** Resolving Health IDs (Verhoeff check, merges), reading a person's registrations, and scrubbing demographics. */
public final class PatientLookup {

    private final PatientDirectory patients;
    private final MasterPatientIndex mpi;
    private final UnitOfWork tx;
    private final IdentityEvents events;
    private final Object registrationLock;

    public PatientLookup(PatientDirectory patients, MasterPatientIndex mpi, UnitOfWork tx, Outbox outbox,
                         PatientRegistration registrationLock) {
        this.patients = patients;
        this.mpi = mpi;
        this.tx = tx;
        this.events = new IdentityEvents(outbox);
        this.registrationLock = registrationLock;
    }

    /** Accepts formatted or plain Health IDs; rejects bad check digits (400) and unknown ids (404). */
    public HealthIdentity identity(String healthIdText) {
        String digits = HealthId.parse(healthIdText).digits();
        if (!patients.exists(digits)) {
            throw new NotFoundException("unknown Health ID");
        }
        String canonical = patients.canonical(digits);
        return new HealthIdentity(canonical, new HashSet<>(patients.aliases(canonical)));
    }

    /** The patient themselves (any alias) or one of {@code roles}. */
    public HealthIdentity authorizedIdentity(Actor actor, String healthIdText, Role... roles) {
        HealthIdentity id = identity(healthIdText);
        if (actor.isPatientSelf(id.aliases())) {
            return id;
        }
        for (Role r : roles) {
            if (actor.has(r)) {
                return id;
            }
        }
        throw new AccessDeniedException("not permitted to access this patient's identity");
    }

    public List<RegistrationRow> registrations(HealthIdentity id) {
        return patients.registrations(List.copyOf(id.aliases()));
    }

    /** Right to erasure on the identity side: scrub demographics and drop them from the live MPI index. */
    public int eraseDemographics(HealthIdentity id) {
        synchronized (registrationLock) {
            List<String> ids = List.copyOf(id.aliases());
            int n = tx.inTransaction(() -> {
                int scrubbed = patients.eraseDemographics(ids);
                events.erased(id.healthId(), scrubbed);
                return scrubbed;
            });
            patients.recordIds(ids).forEach(mpi::forget);
            return n;
        }
    }
}
