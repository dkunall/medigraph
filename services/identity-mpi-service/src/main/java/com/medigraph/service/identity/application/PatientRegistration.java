package com.medigraph.service.identity.application;

import com.medigraph.common.id.HealthId;
import com.medigraph.common.port.Outbox;
import com.medigraph.common.port.UnitOfWork;
import com.medigraph.mpi.MasterPatientIndex;
import com.medigraph.mpi.MatchDecision;
import com.medigraph.mpi.MatchOutcome;
import com.medigraph.mpi.PatientDemographics;
import com.medigraph.service.identity.application.port.out.PatientDirectory;

import java.time.LocalDate;
import java.util.List;

/**
 * Registration use case. The MPI (Fellegi-Sunter scoring over blocking keys) decides whether this facility record
 * is a known person: AUTO_LINK reuses that person's Health ID, NEW mints one, REVIEW mints a provisional one and
 * queues a steward task.
 * <p>
 * The in-memory MPI index is derived state rebuilt from PostgreSQL at start-up, so this service runs as a single
 * writer: {@link #register} is serialized, and if the DB transaction fails the record is removed from the index
 * again so the two never diverge. Scaling out would mean partitioning by blocking key (documented limitation).
 */
public final class PatientRegistration {

    public record RegisterCommand(String facility, String localMrn, String givenName, String familyName,
                                  LocalDate birthDate, String gender, String phone, String city) {
    }

    public record Registration(String healthId, String formattedHealthId, String recordId, MatchDecision decision,
                               Double matchWeight, String matchedRecordId, List<String> reviewTaskIds) {
    }

    private final MasterPatientIndex mpi;
    private final PatientDirectory patients;
    private final UnitOfWork tx;
    private final IdentityEvents events;

    public PatientRegistration(MasterPatientIndex mpi, PatientDirectory patients, UnitOfWork tx, Outbox outbox) {
        this.mpi = mpi;
        this.patients = patients;
        this.tx = tx;
        this.events = new IdentityEvents(outbox);
    }

    /** Rebuilds the in-memory MPI from the database (derived state; no re-scoring). */
    public void restoreIndex() {
        mpi.restore(patients.allLiveDemographics(), patients.allLinks());
    }

    public synchronized Registration register(RegisterCommand c) {
        String recordId = c.facility() + ":" + c.localMrn();
        if (patients.healthIdOfRecord(recordId).isPresent()) {
            throw new com.medigraph.common.error.ConflictException("facility record " + recordId + " is already registered");
        }
        PatientDemographics p = new PatientDemographics(recordId, c.givenName(), c.familyName(), c.birthDate(),
                c.gender(), c.phone(), c.city(), c.facility());
        MatchOutcome o = mpi.register(p);
        try {
            return tx.inTransaction(() -> persist(p, c.localMrn(), o));
        } catch (RuntimeException e) {
            mpi.forget(recordId);
            throw e;
        }
    }

    private Registration persist(PatientDemographics p, String localMrn, MatchOutcome o) {
        String healthId = o.decision() == MatchDecision.AUTO_LINK
                ? patients.canonical(patients.healthIdOfRecord(o.bestCandidate()).orElseThrow())
                : mintHealthId();
        Double weight = Double.isFinite(o.bestWeight()) ? o.bestWeight() : null;
        patients.insertRegistration(p, healthId, localMrn, o.decision().name(), weight);
        if (o.decision() == MatchDecision.AUTO_LINK) {
            patients.insertLink(o.bestCandidate(), p.recordId(), "AUTO_LINK");
        }
        events.registered(healthId, p.recordId(), o.decision().name());
        return new Registration(healthId, HealthId.parse(healthId).formatted(), p.recordId(), o.decision(),
                weight, o.bestCandidate(), o.reviewTaskIds());
    }

    private String mintHealthId() {
        for (int i = 0; i < 5; i++) {
            String id = HealthId.generate().digits();
            if (patients.insertPatient(id)) {
                return id;
            }
        }
        throw new IllegalStateException("could not mint a unique Health ID");
    }
}
