package com.medigraph.service.records.application;

import com.medigraph.common.crypto.Sha256;
import com.medigraph.common.error.AccessDeniedException;
import com.medigraph.common.error.NotFoundException;
import com.medigraph.common.fhir.DataCategory;
import com.medigraph.common.security.Actor;
import com.medigraph.consent.Purpose;
import com.medigraph.crypto.PatientKeyVault;
import com.medigraph.service.records.application.port.out.AccessPolicy;
import com.medigraph.service.records.application.port.out.HealthRecordRepository;
import com.medigraph.service.records.application.port.out.IntegrityLedger;
import com.medigraph.service.records.application.port.out.PatientDirectory;
import com.medigraph.service.records.application.port.out.PatientDirectory.ResolvedPatient;
import com.medigraph.service.records.application.port.out.ResourceCodec;
import com.medigraph.service.records.domain.AccessVerdict;
import com.medigraph.service.records.domain.HealthRecord;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Read side. Consent is asked per category on every read (consent-service audits each decision to the ledger);
 * categories without a basis are withheld, not failed, so a clinician sees "allergies: withheld" instead of an error.
 * A record whose ciphertext fails GCM authentication is quarantined (reported by id) rather than failing the chart.
 */
public final class RecordQueries {

    private static final Logger log = Logger.getLogger(RecordQueries.class.getName());

    public record RecordView(UUID recordId, String category, String resourceType, String contentHash, String facility,
                             Instant createdAt, Object resource) {
    }

    public record ChartView(String healthId, List<RecordView> records, Map<DataCategory, String> decisions,
                            Set<DataCategory> withheld, List<UUID> quarantined) {
    }

    public record RecordVerification(UUID recordId, boolean valid, String reason, String storedHash,
                                     String recomputedHash, Map<String, Object> proof) {
    }

    private final HealthRecordRepository records;
    private final PatientKeyVault vault;
    private final PatientDirectory patients;
    private final AccessPolicy policy;
    private final IntegrityLedger ledger;
    private final ResourceCodec codec;

    public RecordQueries(HealthRecordRepository records, PatientKeyVault vault, PatientDirectory patients,
                         AccessPolicy policy, IntegrityLedger ledger, ResourceCodec codec) {
        this.records = records;
        this.vault = vault;
        this.patients = patients;
        this.policy = policy;
        this.ledger = ledger;
        this.codec = codec;
    }

    public ChartView chart(Actor actor, String healthIdText, Set<DataCategory> requested, Purpose purpose) {
        ResolvedPatient p = patients.resolve(healthIdText);
        Set<DataCategory> cats = requested == null || requested.isEmpty() ? clinicalCategories() : EnumSet.copyOf(requested);
        Map<DataCategory, String> decisions = new EnumMap<>(DataCategory.class);
        Set<DataCategory> withheld = EnumSet.noneOf(DataCategory.class);
        List<RecordView> out = new ArrayList<>();
        List<UUID> quarantined = new ArrayList<>();
        for (DataCategory c : cats) {
            AccessVerdict v = policy.decide(actor, p.healthId(), c, purpose);
            decisions.put(c, v.basis());
            if (!v.allowed()) {
                withheld.add(c);
                continue;
            }
            for (HealthRecord r : records.forPatients(p.aliases(), c)) {
                try {
                    out.add(view(r, decrypt(r)));
                } catch (IllegalStateException tampered) {
                    log.log(Level.SEVERE, "record {0} failed authenticated decryption; quarantined", r.id());
                    quarantined.add(r.id());
                }
            }
        }
        if (withheld.size() == cats.size()) {
            throw new AccessDeniedException("no consent or break-glass grant covers " + cats + " for " + purpose);
        }
        return new ChartView(p.healthId(), out, decisions, withheld, quarantined);
    }

    /**
     * What a receiving hospital runs: decrypt (needs access to the category), recompute SHA-256, and check it against
     * the hash the replicated ledger committed, plus the Merkle inclusion proof when the block is sealed.
     */
    public RecordVerification verify(Actor actor, UUID recordId, Purpose purpose) {
        HealthRecord r = records.find(recordId).orElseThrow(() -> new NotFoundException("record " + recordId));
        String canonical = patients.resolve(r.patientId()).healthId();
        AccessVerdict v = policy.decide(actor, canonical, r.category(), purpose);
        if (!v.allowed()) {
            throw new AccessDeniedException("verification decrypts the record; " + v.reason());
        }
        Map<String, Object> proof = ledger.proof(r.ledgerEntryId()).orElse(null);
        byte[] plaintext;
        try {
            plaintext = decrypt(r);
        } catch (IllegalStateException tampered) {
            return new RecordVerification(recordId, false, "ciphertext failed authentication (modified at rest)",
                    r.contentHash(), null, proof);
        }
        String recomputed = Sha256.hex(plaintext);
        if (!recomputed.equals(r.contentHash())) {
            return new RecordVerification(recordId, false, "plaintext does not match the stored hash", r.contentHash(),
                    recomputed, proof);
        }
        IntegrityLedger.Verification lv = ledger.verify(r.ledgerEntryId(), recomputed);
        return new RecordVerification(recordId, lv.valid(), lv.reason(), r.contentHash(), recomputed, proof);
    }

    private static Set<DataCategory> clinicalCategories() {
        Set<DataCategory> all = EnumSet.allOf(DataCategory.class);
        all.remove(DataCategory.DEMOGRAPHICS); // demographics live in identity-mpi-service
        return all;
    }

    private byte[] decrypt(HealthRecord r) {
        // record id is the GCM AAD: a ciphertext moved to another row fails here
        return vault.decrypt(r.patientId(), r.id().toString(), r.payload());
    }

    private RecordView view(HealthRecord r, byte[] plaintext) {
        return new RecordView(r.id(), r.category().name(), r.resourceType(), r.contentHash(), r.facility(),
                r.createdAt(), codec.tree(plaintext));
    }
}
