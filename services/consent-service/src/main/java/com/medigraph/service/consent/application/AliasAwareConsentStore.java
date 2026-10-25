package com.medigraph.service.consent.application;

import com.medigraph.consent.BreakGlassGrant;
import com.medigraph.consent.ConsentArtefact;
import com.medigraph.consent.ConsentStore;
import com.medigraph.service.consent.application.port.out.PatientResolver;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Consents are stored under the canonical Health ID at the time they were granted. After an MPI merge the survivor
 * must still see consents granted under the merged-away ID, so reads fan out over every alias. Writes go straight
 * through. Cost: one identity lookup per read, O(aliases) queries (aliases are almost always 1 or 2).
 */
public final class AliasAwareConsentStore implements ConsentStore {

    private final ConsentStore delegate;
    private final PatientResolver resolver;

    public AliasAwareConsentStore(ConsentStore delegate, PatientResolver resolver) {
        this.delegate = delegate;
        this.resolver = resolver;
    }

    @Override
    public void save(ConsentArtefact a) {
        delegate.save(a);
    }

    @Override
    public Optional<ConsentArtefact> find(String consentId) {
        return delegate.find(consentId);
    }

    @Override
    public List<ConsentArtefact> forPatient(String patientId) {
        return resolver.resolve(patientId).aliases().stream().flatMap(id -> delegate.forPatient(id).stream())
                .sorted(Comparator.comparing(ConsentArtefact::issuedAt)).toList();
    }

    @Override
    public boolean revoke(String consentId, Instant at) {
        return delegate.revoke(consentId, at);
    }

    @Override
    public void saveBreakGlass(BreakGlassGrant g) {
        delegate.saveBreakGlass(g);
    }

    @Override
    public List<BreakGlassGrant> breakGlassFor(String patientId) {
        return resolver.resolve(patientId).aliases().stream().flatMap(id -> delegate.breakGlassFor(id).stream())
                .sorted(Comparator.comparing(BreakGlassGrant::grantedAt)).toList();
    }
}
