package com.medigraph.common.security;

import com.medigraph.common.id.HealthId;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * The authenticated principal as the application layer sees it, built from a verified JWT by the web adapter.
 * Invariants: a non-blank id, at least one role, a facility for staff, and for {@link Role#PATIENT} the id is the
 * patient's own (Verhoeff-valid) Health ID, so "is this the patient themselves" is a plain equality check.
 */
public record Actor(String id, Set<Role> roles, String facility) {

    public Actor {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("actor id is required");
        }
        Objects.requireNonNull(roles, "roles");
        if (roles.isEmpty()) {
            throw new IllegalArgumentException("actor needs at least one role");
        }
        roles = Set.copyOf(EnumSet.copyOf(roles));
        if (roles.contains(Role.PATIENT)) {
            id = HealthId.parse(id).digits();
        }
        boolean staff = roles.stream().anyMatch(r -> r != Role.PATIENT && r != Role.SERVICE);
        if (staff && (facility == null || facility.isBlank())) {
            throw new IllegalArgumentException("staff actors must belong to a facility");
        }
    }

    public boolean has(Role role) {
        return roles.contains(role);
    }

    public boolean isPatient() {
        return roles.contains(Role.PATIENT);
    }

    public boolean isClinician() {
        return roles.stream().anyMatch(Role::isClinician);
    }

    /** True when this actor is the patient identified by {@code healthIdDigits} (one of their aliases). */
    public boolean isPatientSelf(Set<String> healthIdAliases) {
        return isPatient() && healthIdAliases.contains(id);
    }
}
