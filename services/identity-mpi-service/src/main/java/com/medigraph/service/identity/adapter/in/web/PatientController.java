package com.medigraph.service.identity.adapter.in.web;

import com.medigraph.common.security.Actor;
import com.medigraph.common.security.Role;
import com.medigraph.platform.security.CurrentActor;
import com.medigraph.service.identity.application.PatientLookup;
import com.medigraph.service.identity.application.PatientRegistration;
import com.medigraph.service.identity.application.PatientRegistration.RegisterCommand;
import com.medigraph.service.identity.application.PatientRegistration.Registration;
import com.medigraph.service.identity.application.port.out.PatientDirectory.RegistrationRow;
import com.medigraph.service.identity.domain.HealthIdentity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

@Tag(name = "patients")
@RestController
@RequestMapping("/api/v1/patients")
public class PatientController {

    public record RegisterRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9_\\-]{1,40}") String localMrn,
                                  @NotBlank @Size(max = 100) String givenName, @NotBlank @Size(max = 100) String familyName,
                                  @NotNull @Past LocalDate birthDate, @Size(max = 16) String gender,
                                  @Pattern(regexp = "[0-9+\\- ]{0,32}") String phone, @Size(max = 100) String city) {
    }

    public record PatientView(String healthId, String formatted, Set<String> aliases, List<RegistrationRow> registrations) {
    }

    private final PatientRegistration registration;
    private final PatientLookup lookup;

    public PatientController(PatientRegistration registration, PatientLookup lookup) {
        this.registration = registration;
        this.lookup = lookup;
    }

    @Operation(summary = "Register a facility record; the MPI links it to an existing Health ID or mints one")
    @PreAuthorize("hasAnyRole('RECEPTION','DOCTOR','NURSE','ADMIN')")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Registration register(@CurrentActor Actor actor, @Valid @RequestBody RegisterRequest r) {
        return registration.register(new RegisterCommand(actor.facility(), r.localMrn(), r.givenName(), r.familyName(),
                r.birthDate(), r.gender(), r.phone(), r.city()));
    }

    @PreAuthorize("hasAnyRole('PATIENT','STEWARD','ADMIN')")
    @GetMapping("/{healthId}")
    public PatientView get(@CurrentActor Actor actor, @PathVariable String healthId) {
        HealthIdentity id = lookup.authorizedIdentity(actor, healthId, Role.STEWARD, Role.ADMIN);
        return new PatientView(id.healthId(), id.formatted(), new TreeSet<>(id.aliases()), lookup.registrations(id));
    }
}
