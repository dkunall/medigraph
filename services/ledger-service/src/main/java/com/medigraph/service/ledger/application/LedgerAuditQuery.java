package com.medigraph.service.ledger.application;

import com.medigraph.common.error.AccessDeniedException;
import com.medigraph.common.id.HealthId;
import com.medigraph.common.security.Actor;
import com.medigraph.common.security.Role;
import com.medigraph.ledger.chain.LedgerEntry;

import java.util.List;

/** "Who touched my data": a patient may read the ledger entries about themselves; stewards/admins any patient's. */
public final class LedgerAuditQuery {

    private final LedgerReplica replica;

    public LedgerAuditQuery(LedgerReplica replica) {
        this.replica = replica;
    }

    public List<LedgerEntry> entriesAbout(Actor actor, String healthId) {
        String subject = HealthId.parse(healthId).digits();
        boolean self = actor.isPatient() && actor.id().equals(subject);
        if (!self && !actor.has(Role.STEWARD) && !actor.has(Role.ADMIN)) {
            throw new AccessDeniedException("only the patient or a data steward may read this audit trail");
        }
        return replica.entriesFor(subject);
    }
}
