package com.medigraph.service.icu.adapter.in.web;

import com.medigraph.icu.VitalType;
import com.medigraph.service.icu.application.AlertRecorder;
import com.medigraph.service.icu.application.VitalsIngestion;
import com.medigraph.service.icu.application.port.out.PatientResolver;
import com.medigraph.service.icu.domain.IcuAlert;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@Validated
@RequestMapping("/api/v1/icu")
public class IcuController {

    public record VitalDto(@NotNull @Pattern(regexp = "[A-Za-z0-9._:-]{1,64}") String readingId,
                           @NotNull VitalType type, double value, @Positive long eventTimeMillis) {
    }

    public record VitalsRequest(@NotEmpty @Size(max = 500) List<@Valid VitalDto> readings) {
    }

    private final VitalsIngestion ingestion;
    private final AlertRecorder alerts;
    private final PatientResolver patients;

    public IcuController(VitalsIngestion ingestion, AlertRecorder alerts, PatientResolver patients) {
        this.ingestion = ingestion;
        this.alerts = alerts;
        this.patients = patients;
    }

    @Operation(summary = "Bedside monitor ingestion; event time is the device's, duplicates/late readings are handled")
    @PreAuthorize("hasAnyRole('DOCTOR','NURSE','SERVICE')")
    @PostMapping("/patients/{healthId}/vitals")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Integer> vitals(@PathVariable String healthId, @Valid @RequestBody VitalsRequest r) {
        return Map.of("accepted", ingestion.submit(healthId, r.readings().stream()
                .map(v -> new VitalsIngestion.Reading(v.readingId(), v.type(), v.value(), v.eventTimeMillis())).toList()));
    }

    @Operation(summary = "Most recent NEWS2 alerts for a patient")
    @PreAuthorize("hasAnyRole('DOCTOR','NURSE')")
    @GetMapping("/patients/{healthId}/alerts")
    public List<IcuAlert> alerts(@PathVariable String healthId,
                                 @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit) {
        return alerts.recent(patients.canonical(healthId), limit);
    }

    @Operation(summary = "Operator hook: advance the watermark now (normally the scheduler does this)")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/watermark")
    public Map<String, Long> watermark(@RequestParam @Positive long nowMillis) {
        ingestion.advanceWatermark(nowMillis);
        return Map.of("watermark", nowMillis);
    }
}
