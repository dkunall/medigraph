package com.medigraph.service.beds.domain;

/** Declared ICU capacity of a hospital, as reported by its bed manager. */
public record Capacity(int icuBeds, int occupied) {

    public static final int MAX_BEDS = 10_000;

    public Capacity {
        if (icuBeds < 0 || icuBeds > MAX_BEDS || occupied < 0 || occupied > icuBeds) {
            throw new IllegalArgumentException("need 0 <= occupied <= icuBeds <= " + MAX_BEDS);
        }
    }
}
