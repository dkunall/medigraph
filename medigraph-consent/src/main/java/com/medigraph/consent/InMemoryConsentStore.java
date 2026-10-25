package com.medigraph.consent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class InMemoryConsentStore implements ConsentStore {

    private final ConcurrentMap<String, ConsentArtefact> consents = new ConcurrentHashMap<>();
    private final List<BreakGlassGrant> breakGlass = new CopyOnWriteArrayList<>();

    @Override
    public void save(ConsentArtefact artefact) {
        consents.put(artefact.consentId(), artefact);
    }

    @Override
    public Optional<ConsentArtefact> find(String consentId) {
        return Optional.ofNullable(consents.get(consentId));
    }

    @Override
    public List<ConsentArtefact> forPatient(String patientId) {
        return consents.values().stream().filter(c -> c.patientId().equals(patientId)).toList();
    }

    @Override
    public boolean revoke(String consentId, Instant at) {
        boolean[] changed = {false};
        consents.computeIfPresent(consentId, (k, c) -> {
            if (c.revokedAt() != null) {
                return c;
            }
            changed[0] = true;
            return c.revoked(at);
        });
        return changed[0];
    }

    @Override
    public void saveBreakGlass(BreakGlassGrant grant) {
        breakGlass.add(grant);
    }

    @Override
    public List<BreakGlassGrant> breakGlassFor(String patientId) {
        return new ArrayList<>(breakGlass.stream().filter(g -> g.patientId().equals(patientId)).toList());
    }
}
