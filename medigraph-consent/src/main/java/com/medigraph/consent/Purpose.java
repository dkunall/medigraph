package com.medigraph.consent;

/** Purpose of use (cf. HL7 v3 PurposeOfUse: TREAT, ETREAT, HRESCH, ...). Consent is purpose-bound. */
public enum Purpose {
    TREATMENT,
    EMERGENCY,
    RESEARCH,
    INSURANCE
}
