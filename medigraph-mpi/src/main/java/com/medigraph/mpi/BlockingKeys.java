package com.medigraph.mpi;

import com.medigraph.mpi.algo.Soundex;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Blocking turns O(n) candidate comparison into a lookup: only records sharing at least one key are scored.
 * Two independent passes increase recall, since a record missed by one key (e.g. a married-name change breaks the
 * Soundex key) can still be found by the other (same phone).
 * <ul>
 *   <li>{@code DS:<birth-year>:<soundex(family)>}</li>
 *   <li>{@code PH:<last-10-digits>}</li>
 * </ul>
 */
public final class BlockingKeys {

    private BlockingKeys() {
    }

    public static Set<String> of(PatientDemographics p) {
        Set<String> keys = new LinkedHashSet<>(2);
        String sx = Soundex.encode(p.familyName());
        if (p.birthDate() != null && !sx.isEmpty()) {
            keys.add("DS:" + p.birthDate().getYear() + ":" + sx);
        }
        String phone = p.normalizedPhone();
        if (phone != null) {
            keys.add("PH:" + phone);
        }
        return keys;
    }
}
