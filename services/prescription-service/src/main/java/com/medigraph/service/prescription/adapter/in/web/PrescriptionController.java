package com.medigraph.service.prescription.adapter.in.web;

import com.medigraph.common.security.Actor;
import com.medigraph.consent.Purpose;
import com.medigraph.platform.security.CurrentActor;
import com.medigraph.prescription.DrugNameResolver;
import com.medigraph.service.prescription.application.DrugLookup;
import com.medigraph.service.prescription.application.PrescribeCommand;
import com.medigraph.service.prescription.application.PrescribingService;
import com.medigraph.service.prescription.application.PrescriptionResult;
import com.medigraph.service.prescription.domain.Prescription;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Status codes: 201 issued and recorded; 202 approved but the record write is pending (retry via /issue);
 * 200 not issued (BLOCKED needs an override reason, UNRESOLVED_DRUG needs a clearer name), with every alert listed.
 */
@RestController
@Validated
@RequestMapping("/api/v1")
public class PrescriptionController {

    public record PrescribeRequest(@NotBlank @Size(max = 128) String drug, @Size(max = 256) String dosage,
                                   @Size(max = 2000) String overrideReason, Purpose purpose) {
    }

    private final PrescribingService prescribing;
    private final DrugLookup drugs;

    public PrescriptionController(PrescribingService prescribing, DrugLookup drugs) {
        this.prescribing = prescribing;
        this.drugs = drugs;
    }

    @Operation(summary = "Safety-checked prescribing (SAMPLE interaction data, not clinical advice)")
    @PreAuthorize("hasRole('DOCTOR')")
    @PostMapping("/patients/{healthId}/prescriptions")
    public ResponseEntity<PrescriptionResult> prescribe(@CurrentActor Actor actor, @PathVariable String healthId,
                                                        @Valid @RequestBody PrescribeRequest r) {
        PrescriptionResult result = prescribing.prescribe(actor,
                new PrescribeCommand(healthId, r.drug(), r.dosage(), r.overrideReason(), r.purpose()));
        HttpStatus status = switch (result.status()) {
            case RECORDED -> HttpStatus.CREATED;
            case PENDING_RECORD -> HttpStatus.ACCEPTED;
            case NOT_ISSUED -> HttpStatus.OK;
        };
        return ResponseEntity.status(status).body(result);
    }

    @Operation(summary = "Retry writing an approved prescription into the record (idempotent)")
    @PreAuthorize("hasAnyRole('DOCTOR','ADMIN')")
    @PostMapping("/prescriptions/{id}/issue")
    public Prescription issue(@CurrentActor Actor actor, @PathVariable UUID id) {
        return prescribing.issue(actor, id);
    }

    @Operation(summary = "Drug-name autocomplete (trie prefix search)")
    @PreAuthorize("hasAnyRole('DOCTOR','NURSE')")
    @GetMapping("/drugs")
    public List<String> suggest(@RequestParam @NotBlank @Size(max = 64) String prefix,
                                @RequestParam(defaultValue = "10") int limit) {
        return drugs.suggest(prefix, limit);
    }

    @Operation(summary = "Resolve a free-typed drug name (exact, BK-tree typo correction, or ambiguous)")
    @PreAuthorize("hasAnyRole('DOCTOR','NURSE')")
    @GetMapping("/drugs/resolution")
    public DrugNameResolver.Resolution resolve(@RequestParam @NotBlank @Size(max = 128) String name) {
        return drugs.resolve(name);
    }
}
