package com.medigraph.service.identity.application;

import com.medigraph.common.error.NotFoundException;
import com.medigraph.common.port.Outbox;
import com.medigraph.common.port.UnitOfWork;
import com.medigraph.mpi.MasterPatientIndex;
import com.medigraph.mpi.ReviewTask;
import com.medigraph.service.identity.application.port.out.PatientDirectory;

import java.util.List;

/** Data-steward decisions on ambiguous matches. A merge keeps the older (candidate's) identity as the survivor. */
public final class StewardReview {

    public record Resolution(String taskId, String status, String healthId, String mergedHealthId) {
    }

    private final MasterPatientIndex mpi;
    private final PatientDirectory patients;
    private final UnitOfWork tx;
    private final IdentityEvents events;
    private final Object registrationLock;

    /** {@code registrationLock} is the {@link PatientRegistration} instance: both mutate the same MPI index. */
    public StewardReview(MasterPatientIndex mpi, PatientDirectory patients, UnitOfWork tx, Outbox outbox,
                         PatientRegistration registrationLock) {
        this.mpi = mpi;
        this.patients = patients;
        this.tx = tx;
        this.events = new IdentityEvents(outbox);
        this.registrationLock = registrationLock;
    }

    public List<ReviewTask> pending() {
        return mpi.pendingReviews();
    }

    public Resolution resolve(String taskId, boolean merge) {
        synchronized (registrationLock) {
            return tx.inTransaction(() -> {
                ReviewTask t = mpi.pendingReviews().stream().filter(x -> x.id().equals(taskId)).findFirst()
                        .orElseThrow(() -> new NotFoundException("no pending review task " + taskId));
                mpi.resolveReview(taskId, merge);
                if (!merge) {
                    return new Resolution(taskId, "REJECTED", null, null);
                }
                patients.insertLink(t.candidateRecordId(), t.recordId(), "STEWARD");
                String survivor = patients.canonical(patients.healthIdOfRecord(t.candidateRecordId()).orElseThrow());
                String merged = patients.canonical(patients.healthIdOfRecord(t.recordId()).orElseThrow());
                if (!survivor.equals(merged)) {
                    patients.merge(merged, survivor);
                    events.merged(survivor, merged);
                }
                return new Resolution(taskId, "MERGED", survivor, merged);
            });
        }
    }
}
