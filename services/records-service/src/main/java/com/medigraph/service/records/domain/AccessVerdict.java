package com.medigraph.service.records.domain;

/** consent-service's answer for one (requester, patient, category, purpose). {@code basis} is SELF/CONSENT/BREAK_GLASS/DENIED. */
public record AccessVerdict(boolean allowed, String basis, String reason) {
}
