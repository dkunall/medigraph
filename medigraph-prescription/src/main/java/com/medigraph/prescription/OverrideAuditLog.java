package com.medigraph.prescription;

import java.util.List;

/** Append-only audit of doctor overrides (the app persists it and anchors a hash on the ledger). */
public interface OverrideAuditLog {
    void record(OverrideRecord r);

    List<OverrideRecord> forPatient(String patientId);
}
