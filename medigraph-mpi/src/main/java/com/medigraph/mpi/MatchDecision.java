package com.medigraph.mpi;

public enum MatchDecision {
    /** Weight >= upper threshold: same person, link automatically. */
    AUTO_LINK,
    /** Between thresholds: a human data steward decides. */
    REVIEW,
    /** Below lower threshold: a new person. */
    NEW
}
