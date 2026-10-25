package com.medigraph.service.icu.application.port.out;

/** Health ID (any alias) -> canonical Health ID, via identity-mpi-service. */
public interface PatientResolver {

    String canonical(String healthId);
}
