package com.medigraph.service.identity.application.port.out;

import com.medigraph.mpi.PatientDemographics;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Persistence port for Health IDs, registrations and MPI links. */
public interface PatientDirectory {

    record RegistrationRow(String recordId, String healthId, String facility, String localMrn, String matchDecision,
                           Double matchWeight, java.time.Instant createdAt, java.time.Instant erasedAt) {
    }

    /** False on Health ID collision (the caller generates another). */
    boolean insertPatient(String healthId);

    boolean exists(String healthId);

    void insertRegistration(PatientDemographics p, String healthId, String localMrn, String decision, Double weight);

    void insertLink(String recordA, String recordB, String reason);

    Optional<String> healthIdOfRecord(String recordId);

    /** Follows merged_into to the surviving identity. */
    String canonical(String healthId);

    /** The identity plus every identity merged into it (directly or transitively). */
    List<String> aliases(String canonicalId);

    void merge(String fromHealthId, String intoHealthId);

    List<RegistrationRow> registrations(List<String> healthIds);

    List<String> recordIds(List<String> healthIds);

    int eraseDemographics(List<String> healthIds);

    List<PatientDemographics> allLiveDemographics();

    List<Map.Entry<String, String>> allLinks();
}
