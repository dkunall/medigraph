package com.medigraph.service.prescription.application.port.out;

import com.medigraph.service.prescription.domain.Prescription;

import java.util.Optional;
import java.util.UUID;

public interface PrescriptionRepository {

    void insert(Prescription p);

    Optional<Prescription> find(UUID id);

    void markRecorded(UUID id, String recordId);
}
