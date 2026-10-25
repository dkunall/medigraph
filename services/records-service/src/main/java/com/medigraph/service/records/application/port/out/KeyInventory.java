package com.medigraph.service.records.application.port.out;

import java.util.List;

/** Enumerates patients whose DEK is wrapped by a KEK other than the current one (keyset pagination). */
public interface KeyInventory {

    List<String> wrappedByOtherThan(String currentKekId, String afterPatientId, int limit);
}
