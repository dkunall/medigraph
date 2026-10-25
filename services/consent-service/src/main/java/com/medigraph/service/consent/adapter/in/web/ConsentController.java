package com.medigraph.service.consent.adapter.in.web;

import com.medigraph.common.fhir.DataCategory;
import com.medigraph.common.security.Actor;
import com.medigraph.consent.BreakGlassGrant;
import com.medigraph.consent.Purpose;
import com.medigraph.platform.security.CurrentActor;
import com.medigraph.service.consent.application.ConsentCommands;
import com.medigraph.service.consent.application.ConsentCommands.ConsentView;
import com.medigraph.service.consent.application.port.out.NotificationInbox.Notification;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

@Tag(name = "consent")
@RestController
@RequestMapping("/api/v1")
public class ConsentController {

    public record GrantRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9_.:@\\-]{1,64}") String grantee,
                               @NotEmpty Set<DataCategory> categories, @NotNull Purpose purpose,
                               @Min(1) @Max(365) int durationDays) {
    }

    public record BreakGlassRequest(@NotBlank @Size(max = 1000) String reason) {
    }

    public record BreakGlassView(String grantId, Set<DataCategory> scope, Instant expiresAt, String reasonHash) {
        static BreakGlassView of(BreakGlassGrant g) {
            return new BreakGlassView(g.grantId(), g.scope(), g.expiresAt(), g.reasonHash());
        }
    }

    private final ConsentCommands consents;

    public ConsentController(ConsentCommands consents) {
        this.consents = consents;
    }

    @Operation(summary = "The patient signs (Ed25519) a consent granting a clinician or facility access to categories")
    @PreAuthorize("hasRole('PATIENT')")
    @PostMapping("/patients/{healthId}/consents")
    @ResponseStatus(HttpStatus.CREATED)
    public ConsentView grant(@CurrentActor Actor actor, @PathVariable String healthId, @Valid @RequestBody GrantRequest r) {
        return consents.grant(actor, healthId, r.grantee(), r.categories(), r.purpose(), Duration.ofDays(r.durationDays()));
    }

    @PreAuthorize("hasAnyRole('PATIENT','STEWARD','ADMIN')")
    @GetMapping("/patients/{healthId}/consents")
    public List<ConsentView> list(@CurrentActor Actor actor, @PathVariable String healthId) {
        return consents.list(actor, healthId);
    }

    @PreAuthorize("hasRole('PATIENT')")
    @DeleteMapping("/consents/{consentId}")
    public ConsentView revoke(@CurrentActor Actor actor, @PathVariable String consentId) {
        return consents.revoke(actor, consentId);
    }

    @Operation(summary = "Emergency access without consent: reasoned, emergency scope only, time-boxed, ledgered, patient notified")
    @PreAuthorize("hasAnyRole('DOCTOR','NURSE')")
    @PostMapping("/patients/{healthId}/break-glass")
    @ResponseStatus(HttpStatus.CREATED)
    public BreakGlassView breakGlass(@CurrentActor Actor actor, @PathVariable String healthId,
                                     @Valid @RequestBody BreakGlassRequest r) {
        return BreakGlassView.of(consents.breakGlass(actor, healthId, r.reason()));
    }

    @PreAuthorize("hasRole('PATIENT')")
    @GetMapping("/patients/{healthId}/notifications")
    public List<Notification> notifications(@CurrentActor Actor actor, @PathVariable String healthId) {
        return consents.notifications(actor, healthId);
    }
}
