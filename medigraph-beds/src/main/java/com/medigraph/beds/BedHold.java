package com.medigraph.beds;

import java.time.Instant;

public record BedHold(String holdId, String hospitalId, String patientId, Instant createdAt, Instant expiresAt,
                      Status status) {

    public enum Status { ACTIVE, CONFIRMED, RELEASED, EXPIRED }

    public BedHold withStatus(Status s) {
        return new BedHold(holdId, hospitalId, patientId, createdAt, expiresAt, s);
    }
}
