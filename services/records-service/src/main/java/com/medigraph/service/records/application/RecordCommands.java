package com.medigraph.service.records.application;

import com.medigraph.common.crypto.Sha256;
import com.medigraph.common.error.AccessDeniedException;
import com.medigraph.common.fhir.FhirResource;
import com.medigraph.common.port.Outbox;
import com.medigraph.common.port.UnitOfWork;
import com.medigraph.common.security.Actor;
import com.medigraph.common.security.Role;
import com.medigraph.crypto.EncryptedPayload;
import com.medigraph.crypto.PatientKeyVault;
import com.medigraph.ledger.chain.EntryType;
import com.medigraph.ledger.chain.LedgerEntry;
import com.medigraph.service.records.application.port.out.HealthRecordRepository;
import com.medigraph.service.records.application.port.out.LedgerAnchor;
import com.medigraph.service.records.application.port.out.PatientDirectory;
import com.medigraph.service.records.application.port.out.PatientDirectory.ResolvedPatient;
import com.medigraph.service.records.application.port.out.ResourceCodec;
import com.medigraph.service.records.domain.ErasureResult;
import com.medigraph.service.records.domain.HealthRecord;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * Write side. Remote calls (identity) happen before the transaction so no DB connection is held across the network;
 * everything that must be atomic (DEK creation, ciphertext, ledger anchor, domain event) is one local transaction.
 */
public final class RecordCommands {

    public record CreatedRecord(UUID recordId, String healthId, String resourceType, String category,
                                String contentHash, String ledgerEntryId) {
    }

    private final HealthRecordRepository records;
    private final PatientKeyVault vault;
    private final PatientDirectory patients;
    private final LedgerAnchor ledger;
    private final ResourceCodec codec;
    private final RecordEvents events;
    private final UnitOfWork tx;
    private final Clock clock;

    public RecordCommands(HealthRecordRepository records, PatientKeyVault vault, PatientDirectory patients,
                          LedgerAnchor ledger, ResourceCodec codec, Outbox outbox, UnitOfWork tx, Clock clock) {
        this.records = records;
        this.vault = vault;
        this.patients = patients;
        this.ledger = ledger;
        this.codec = codec;
        this.events = new RecordEvents(outbox);
        this.tx = tx;
        this.clock = clock;
    }

    public CreatedRecord create(Actor author, String healthIdText, FhirResource resource) {
        String patient = patients.resolve(healthIdText).healthId();
        byte[] plaintext = codec.encode(resource);
        String hash = Sha256.hex(plaintext);
        UUID id = UUID.randomUUID();
        String type = resource.getClass().getSimpleName();
        String entryId = HealthRecord.ledgerEntryIdFor(id);
        HealthRecord stored = tx.inTransaction(() -> {
            EncryptedPayload enc = vault.encrypt(patient, id.toString(), plaintext);
            HealthRecord r = new HealthRecord(id, patient, type, resource.category(), enc, hash, entryId,
                    author.facility(), author.id(), clock.instant());
            records.insert(r);
            ledger.anchor(new LedgerEntry(entryId, EntryType.RECORD_HASH, patient, author.id(), hash, clock.millis(),
                    Map.of("recordId", id.toString(), "resourceType", type, "facility", author.facility())));
            events.created(r);
            return r;
        });
        return new CreatedRecord(stored.id(), patient, type, stored.category().name(), hash, entryId);
    }

    /**
     * Right to erasure, as an idempotent saga: (1) destroy every DEK of the identity and its merged aliases and
     * anchor KEY_SHREDDED, atomically; (2) ask identity-mpi-service to scrub demographics. If step 2 fails the
     * caller gets 503 and retries; step 1 is then a no-op (keys already gone), so a retry converges.
     */
    public ErasureResult erase(Actor actor, String healthIdText) {
        ResolvedPatient p = patients.resolve(healthIdText);
        if (!actor.isPatientSelf(p.aliases()) && !actor.has(Role.ADMIN)) {
            throw new AccessDeniedException("only the patient or an administrator can erase this identity");
        }
        long now = clock.millis();
        String entryId = "shred:" + p.healthId() + ":" + now;
        int destroyed = tx.inTransaction(() -> {
            int n = 0;
            for (String id : p.aliases()) {
                n += vault.shred(id) ? 1 : 0;
            }
            if (n > 0) {
                ledger.anchor(new LedgerEntry(entryId, EntryType.KEY_SHREDDED, p.healthId(), actor.id(),
                        Sha256.hex("shred|" + p.healthId()), now, Map.of("keyVersion", "1")));
                events.erased(p.healthId(), n);
            }
            return n;
        });
        int scrubbed = patients.erase(p.healthId());
        return new ErasureResult(p.healthId(), destroyed, scrubbed, destroyed > 0 ? entryId : null);
    }
}
