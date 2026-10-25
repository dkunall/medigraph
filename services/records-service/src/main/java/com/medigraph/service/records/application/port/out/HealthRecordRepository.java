package com.medigraph.service.records.application.port.out;

import com.medigraph.common.fhir.DataCategory;
import com.medigraph.service.records.domain.HealthRecord;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HealthRecordRepository {

    void insert(HealthRecord r);

    Optional<HealthRecord> find(UUID id);

    /** Records stored under any of the IDs (records written before an MPI merge keep the old ID), oldest first. */
    List<HealthRecord> forPatients(Collection<String> healthIds, DataCategory category);
}
