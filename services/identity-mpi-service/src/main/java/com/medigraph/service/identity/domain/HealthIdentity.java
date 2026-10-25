package com.medigraph.service.identity.domain;

import com.medigraph.common.id.HealthId;

import java.util.Set;

/**
 * A person's surviving (canonical) Health ID plus every Health ID merged into it. Invariant: the canonical id is
 * one of the aliases and every alias is a Verhoeff-valid Health ID.
 */
public record HealthIdentity(String healthId, Set<String> aliases) {

    public HealthIdentity {
        healthId = HealthId.parse(healthId).digits();
        aliases = Set.copyOf(aliases);
        if (!aliases.contains(healthId)) {
            throw new IllegalArgumentException("aliases must include the canonical Health ID");
        }
        aliases.forEach(HealthId::parse);
    }

    public String formatted() {
        return HealthId.parse(healthId).formatted();
    }
}
