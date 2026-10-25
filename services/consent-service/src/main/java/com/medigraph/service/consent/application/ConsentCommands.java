package com.medigraph.service.consent.application;

import com.medigraph.common.error.AccessDeniedException;
import com.medigraph.common.error.NotFoundException;
import com.medigraph.common.fhir.DataCategory;
import com.medigraph.common.port.UnitOfWork;
import com.medigraph.common.security.Actor;
import com.medigraph.common.security.Role;
import com.medigraph.consent.BreakGlassGrant;
import com.medigraph.consent.ConsentArtefact;
import com.medigraph.consent.ConsentService;
import com.medigraph.consent.Purpose;
import com.medigraph.service.consent.application.port.out.NotificationInbox;
import com.medigraph.service.consent.application.port.out.NotificationInbox.Notification;
import com.medigraph.service.consent.application.port.out.PatientResolver;
import com.medigraph.service.consent.application.port.out.PatientResolver.ResolvedPatient;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Patient-facing consent use cases. Authorisation that depends on data (is this caller the patient, under any of
 * their merged Health IDs?) lives here; coarse role checks live on the web adapter. Remote identity lookups happen
 * before the transaction opens so no DB connection is held across an HTTP call.
 */
public final class ConsentCommands {

    public record ConsentView(ConsentArtefact consent, boolean signatureValid) {
    }

    private final ConsentService consent;
    private final PatientResolver patients;
    private final NotificationInbox inbox;
    private final UnitOfWork tx;

    public ConsentCommands(ConsentService consent, PatientResolver patients, NotificationInbox inbox, UnitOfWork tx) {
        this.consent = consent;
        this.patients = patients;
        this.inbox = inbox;
        this.tx = tx;
    }

    private ConsentView view(ConsentArtefact a) {
        return new ConsentView(a, consent.verifySignature(a));
    }

    private ResolvedPatient self(Actor actor, String healthIdText, Role... overrides) {
        ResolvedPatient p = patients.resolve(healthIdText);
        if (actor.isPatientSelf(p.aliases()) || Set.of(overrides).stream().anyMatch(actor::has)) {
            return p;
        }
        throw new AccessDeniedException("only the patient can do this");
    }

    /** Only the patient signs a consent for themselves; stewards cannot consent on a patient's behalf. */
    public ConsentView grant(Actor actor, String healthIdText, String grantee, Set<DataCategory> categories,
                             Purpose purpose, Duration duration) {
        ResolvedPatient p = self(actor, healthIdText);
        return view(tx.inTransaction(() -> consent.grant(p.healthId(), grantee, categories, purpose, duration)));
    }

    public List<ConsentView> list(Actor actor, String healthIdText) {
        ResolvedPatient p = self(actor, healthIdText, Role.STEWARD, Role.ADMIN);
        return consent.consentsOf(p.healthId()).stream().map(this::view).toList();
    }

    public ConsentView revoke(Actor actor, String consentId) {
        ConsentArtefact a = consent.find(consentId).orElseThrow(() -> new NotFoundException("consent " + consentId));
        self(actor, a.patientId());
        return view(tx.inTransaction(() -> consent.revoke(consentId, a.patientId())));
    }

    public BreakGlassGrant breakGlass(Actor clinician, String healthIdText, String reason) {
        ResolvedPatient p = patients.resolve(healthIdText);
        return tx.inTransaction(() -> consent.breakGlass(p.healthId(), clinician.id(), clinician.facility(), reason));
    }

    public List<Notification> notifications(Actor actor, String healthIdText) {
        ResolvedPatient p = self(actor, healthIdText);
        return inbox.forPatients(p.aliases());
    }
}
