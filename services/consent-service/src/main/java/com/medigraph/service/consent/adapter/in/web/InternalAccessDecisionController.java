package com.medigraph.service.consent.adapter.in.web;

import com.medigraph.common.fhir.DataCategory;
import com.medigraph.consent.AccessDecision;
import com.medigraph.consent.AccessRequest;
import com.medigraph.consent.Purpose;
import com.medigraph.service.consent.application.AccessDecisions;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Policy decision point for other services (SERVICE role only, enforced for all of /internal/**). Every call is audited. */
@Tag(name = "consent-internal")
@RestController
public class InternalAccessDecisionController {

    /** {@code facility} is optional: patients reading their own chart have none. */
    public record DecisionRequest(@NotBlank String requester, @Pattern(regexp = "[A-Za-z0-9_.:@\\-]{1,64}") String facility, @NotBlank String patientId,
                                  @NotNull DataCategory category, @NotNull Purpose purpose) {
    }

    private final AccessDecisions decisions;

    public InternalAccessDecisionController(AccessDecisions decisions) {
        this.decisions = decisions;
    }

    @PostMapping("/internal/access-decisions")
    public AccessDecision decide(@Valid @RequestBody DecisionRequest r) {
        return decisions.decide(new AccessRequest(r.requester(), r.facility(), r.patientId(), r.category(), r.purpose()));
    }
}
