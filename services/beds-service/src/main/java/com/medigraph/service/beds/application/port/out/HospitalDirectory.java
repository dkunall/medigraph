package com.medigraph.service.beds.application.port.out;

import com.medigraph.beds.Hospital;

import java.util.Optional;

public interface HospitalDirectory {

    void upsert(Hospital hospital);

    Optional<Hospital> find(String hospitalId);
}
