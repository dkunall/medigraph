package com.medigraph.consent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistence port for consent artefacts and break-glass grants. */
public interface ConsentStore {

    void save(ConsentArtefact artefact);

    Optional<ConsentArtefact> find(String consentId);

    List<ConsentArtefact> forPatient(String patientId);

    /** Marks revoked; returns false if unknown or already revoked. */
    boolean revoke(String consentId, Instant at);

    void saveBreakGlass(BreakGlassGrant grant);

    List<BreakGlassGrant> breakGlassFor(String patientId);
}
