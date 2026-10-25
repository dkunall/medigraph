package com.medigraph.service.consent.application.port.out;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/** Read side of patient notifications (the write side is the consent library's PatientNotifier). */
public interface NotificationInbox {

    record Notification(String kind, String message, Instant createdAt) {
    }

    List<Notification> forPatients(Collection<String> healthIds);
}
