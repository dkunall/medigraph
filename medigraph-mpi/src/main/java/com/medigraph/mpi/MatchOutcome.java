package com.medigraph.mpi;

import java.util.List;
import java.util.Map;

/**
 * Result of registering one record.
 *
 * @param enterpriseId  the cluster's enterprise identity (root record id) after this registration
 * @param bestCandidate best-scoring existing record, or null when no candidate shared a blocking key
 * @param reviewTaskIds review tasks opened for this registration (empty unless REVIEW, or extra ambiguous clusters)
 */
public record MatchOutcome(String recordId, MatchDecision decision, String enterpriseId, String bestCandidate,
                           double bestWeight, Map<MatchField, Double> explanation, int candidatesScored,
                           List<String> reviewTaskIds) {
}
