package com.medigraph.service.identity.adapter.in.web;

import com.medigraph.service.identity.application.PatientLookup;
import com.medigraph.service.identity.domain.HealthIdentity;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Service-to-service API (SERVICE role): other services resolve Health IDs and merges through here. */
@Tag(name = "identity-internal")
@RestController
@RequestMapping("/internal/patients")
public class InternalPatientController {

    private final PatientLookup lookup;

    public InternalPatientController(PatientLookup lookup) {
        this.lookup = lookup;
    }

    @GetMapping("/{healthId}")
    public HealthIdentity identity(@PathVariable String healthId) {
        return lookup.identity(healthId);
    }

    /** Called by records-service as part of a right-to-erasure request. Idempotent. */
    @PostMapping("/{healthId}/erasure")
    public Map<String, Object> erase(@PathVariable String healthId) {
        HealthIdentity id = lookup.identity(healthId);
        return Map.of("healthId", id.healthId(), "registrationsScrubbed", lookup.eraseDemographics(id));
    }
}
