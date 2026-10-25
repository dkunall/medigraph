package com.medigraph.mpi;

import java.time.Instant;
import java.util.Map;

public record ReviewTask(String id, String recordId, String candidateRecordId, double weight,
                         Map<MatchField, Double> perField, Status status, Instant createdAt) {
    public enum Status { PENDING, MERGED, REJECTED }

    public ReviewTask withStatus(Status s) {
        return new ReviewTask(id, recordId, candidateRecordId, weight, perField, s, createdAt);
    }
}
