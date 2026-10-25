package com.medigraph.service.icu.application.port.out;

import com.medigraph.service.icu.domain.IcuAlert;

import java.util.List;

public interface AlertRepository {

    /** @return false if this patient window was already stored (redelivery) */
    boolean saveIfAbsent(IcuAlert alert);

    List<IcuAlert> recent(String patientId, int limit);
}
