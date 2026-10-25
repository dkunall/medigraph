package com.medigraph.common.id;

import java.util.regex.Pattern;

/** A registered facility code such as {@code HOSP-A}: upper-case letters, digits and dashes, 2..32 chars. */
public record FacilityId(String value) {

    private static final Pattern SHAPE = Pattern.compile("[A-Z0-9][A-Z0-9-]{1,31}");

    public FacilityId {
        if (value == null || !SHAPE.matcher(value).matches()) {
            throw new IllegalArgumentException("facility id must match " + SHAPE.pattern());
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
