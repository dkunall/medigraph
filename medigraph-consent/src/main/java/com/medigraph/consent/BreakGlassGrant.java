package com.medigraph.consent;

import com.medigraph.common.fhir.DataCategory;

import java.time.Instant;
import java.util.Set;

/**
 * Emergency override: time-boxed, scope-limited, and always audited. {@code reason} stays off-ledger
 * (it may contain clinical detail); the ledger stores {@code reasonHash}.
 */
public record BreakGlassGrant(String grantId, String patientId, String requester, String facility, String reason,
                              String reasonHash, Set<DataCategory> scope, Instant grantedAt, Instant expiresAt) {

    public boolean covers(String who, DataCategory category, Instant now) {
        return requester.equals(who) && scope.contains(category) && !now.isBefore(grantedAt) && now.isBefore(expiresAt);
    }
}
