package com.medigraph.icu.stream;

import com.medigraph.icu.News2Score;
import com.medigraph.icu.VitalType;

import java.util.Set;

/**
 * Result of closing one patient window. {@code score} is null when some parameter has no fresh value
 * ({@code missing}). {@code alert} is true when the cooldown/dedup policy decided to page someone.
 */
public record WindowScore(String patientId, long windowStart, long windowEnd, News2Score score,
                          Set<VitalType> missing, boolean alert, String alertReason) {

    public boolean complete() {
        return score != null;
    }
}
