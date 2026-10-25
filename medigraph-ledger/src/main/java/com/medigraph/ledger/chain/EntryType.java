package com.medigraph.ledger.chain;

/** Everything the ledger is allowed to hold. There is deliberately no type that carries clinical content. */
public enum EntryType {
    RECORD_HASH,
    CONSENT_GRANTED,
    CONSENT_REVOKED,
    ACCESS,
    ACCESS_DENIED,
    BREAK_GLASS,
    KEY_SHREDDED,
    PRESCRIPTION_OVERRIDE
}
