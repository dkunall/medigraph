package com.medigraph.service.consent.adapter.out.persistence;

import com.medigraph.common.port.Outbox;
import com.medigraph.common.port.OutboxMessage;
import com.medigraph.consent.PatientNotifier;
import com.medigraph.service.consent.application.port.out.NotificationInbox;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collection;
import java.util.List;

/**
 * Stores the notification (the patient's in-app inbox) and emits a PatientNotificationRequested event in the same
 * transaction for an SMS/push gateway. The event carries the kind only, not the message text.
 */
public final class JdbcPatientNotifier implements PatientNotifier, NotificationInbox {

    private final JdbcTemplate jdbc;
    private final Outbox outbox;

    public JdbcPatientNotifier(JdbcTemplate jdbc, Outbox outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    @Override
    public void notify(String patientId, String kind, String message) {
        jdbc.update("INSERT INTO notification (patient_id, kind, message) VALUES (?,?,?)", patientId, kind, message);
        outbox.enqueue(new OutboxMessage(OutboxMessage.EVENTS, "Patient", patientId, "PatientNotificationRequested",
                "{\"healthId\":\"" + patientId + "\",\"kind\":\"" + kind.replaceAll("[^A-Za-z0-9_]", "") + "\"}"));
    }

    @Override
    public List<Notification> forPatients(Collection<String> healthIds) {
        return jdbc.query("SELECT kind, message, created_at FROM notification WHERE patient_id = ANY (?) ORDER BY id",
                (rs, n) -> new Notification(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant()),
                (Object) healthIds.toArray(String[]::new));
    }
}
