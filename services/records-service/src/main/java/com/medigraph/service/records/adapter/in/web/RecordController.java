package com.medigraph.service.records.adapter.in.web;

import com.medigraph.common.fhir.DataCategory;
import com.medigraph.common.fhir.FhirResource;
import com.medigraph.common.security.Actor;
import com.medigraph.consent.Purpose;
import com.medigraph.platform.security.CurrentActor;
import com.medigraph.service.records.application.RecordCommands;
import com.medigraph.service.records.application.RecordCommands.CreatedRecord;
import com.medigraph.service.records.application.RecordQueries;
import com.medigraph.service.records.application.RecordQueries.ChartView;
import com.medigraph.service.records.application.RecordQueries.RecordVerification;
import com.medigraph.service.records.domain.ErasureResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.UUID;

@Tag(name = "records")
@RestController
@RequestMapping("/api/v1")
public class RecordController {

    private final RecordCommands commands;
    private final RecordQueries queries;

    public RecordController(RecordCommands commands, RecordQueries queries) {
        this.commands = commands;
        this.queries = queries;
    }

    @Operation(summary = "Store a FHIR resource: encrypted under the patient's DEK, SHA-256 anchored on the ledger")
    @PreAuthorize("hasAnyRole('DOCTOR','NURSE')")
    @PostMapping("/patients/{healthId}/records")
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedRecord create(@CurrentActor Actor actor, @PathVariable String healthId,
                                @Valid @RequestBody FhirResource resource) {
        return commands.create(actor, healthId, resource);
    }

    @Operation(summary = "Consent-filtered chart: categories without consent/break-glass are listed as withheld")
    @PreAuthorize("hasAnyRole('DOCTOR','NURSE','PATIENT')")
    @GetMapping("/patients/{healthId}/records")
    public ChartView chart(@CurrentActor Actor actor, @PathVariable String healthId,
                           @RequestParam(name = "category", required = false) Set<DataCategory> categories,
                           @RequestParam(defaultValue = "TREATMENT") Purpose purpose) {
        return queries.chart(actor, healthId, categories, purpose);
    }

    @Operation(summary = "Recompute the record's hash and check it against the replicated ledger (+ Merkle proof)")
    @PreAuthorize("hasAnyRole('DOCTOR','NURSE','PATIENT')")
    @GetMapping("/records/{recordId}/verification")
    public RecordVerification verify(@CurrentActor Actor actor, @PathVariable UUID recordId,
                                     @RequestParam(defaultValue = "TREATMENT") Purpose purpose) {
        return queries.verify(actor, recordId, purpose);
    }

    @Operation(summary = "Right to erasure: crypto-shred every data key of the identity and scrub demographics")
    @PreAuthorize("hasAnyRole('PATIENT','ADMIN')")
    @DeleteMapping("/patients/{healthId}/data")
    public ErasureResult erase(@CurrentActor Actor actor, @PathVariable String healthId) {
        return commands.erase(actor, healthId);
    }
}
