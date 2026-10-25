package com.medigraph.prescription;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class InMemoryOverrideAuditLog implements OverrideAuditLog {
    private final List<OverrideRecord> records = new CopyOnWriteArrayList<>();

    @Override
    public void record(OverrideRecord r) {
        records.add(r);
    }

    @Override
    public List<OverrideRecord> forPatient(String patientId) {
        return records.stream().filter(r -> r.patientId().equals(patientId)).toList();
    }
}
