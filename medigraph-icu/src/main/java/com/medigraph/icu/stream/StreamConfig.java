package com.medigraph.icu.stream;

import com.medigraph.icu.RiskLevel;

import java.time.Duration;

/**
 * @param windowSize       tumbling event-time window per patient
 * @param allowedLateness  how long after a window ends we still accept readings for it (watermark lag)
 * @param freshness        a parameter value older than this (relative to window end) is not carried forward
 * @param cooldown         suppress repeat alerts of the same or lower risk for this long
 * @param alertAt          minimum risk that raises an alert
 */
public record StreamConfig(Duration windowSize, Duration allowedLateness, Duration freshness, Duration cooldown,
                           RiskLevel alertAt) {

    public StreamConfig {
        if (windowSize.isZero() || windowSize.isNegative() || allowedLateness.isNegative()) {
            throw new IllegalArgumentException("invalid window/lateness");
        }
    }

    public static StreamConfig defaults() {
        return new StreamConfig(Duration.ofMinutes(5), Duration.ofMinutes(2), Duration.ofHours(1),
                Duration.ofMinutes(30), RiskLevel.LOW_MEDIUM);
    }
}
