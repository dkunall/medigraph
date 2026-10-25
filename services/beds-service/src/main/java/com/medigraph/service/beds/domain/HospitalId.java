package com.medigraph.service.beds.domain;

import java.util.regex.Pattern;

/** Facility-registry style identifier; also the consistent-hashing key for inventory shards. */
public record HospitalId(String value) {

    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");

    public HospitalId {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("hospital id must match " + FORMAT.pattern());
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
