package com.medigraph.mpi;

import java.time.LocalDate;
import java.util.Objects;

/**
 * The identity-bearing subset of a registration used for matching. {@code recordId} is the source record id
 * (one per registration at one facility); the MPI decides which records are the same person.
 */
public record PatientDemographics(String recordId, String givenName, String familyName, LocalDate birthDate,
                                  String gender, String phone, String city, String facility) {

    public PatientDemographics {
        Objects.requireNonNull(recordId, "recordId");
    }

    /** Digits only, last 10 (drops country code / formatting), or null when absent. */
    public String normalizedPhone() {
        if (phone == null) {
            return null;
        }
        String d = phone.replaceAll("\\D", "");
        if (d.length() < 7) {
            return null;
        }
        return d.length() > 10 ? d.substring(d.length() - 10) : d;
    }
}
