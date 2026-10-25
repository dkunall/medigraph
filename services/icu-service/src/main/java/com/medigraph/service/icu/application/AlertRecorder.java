package com.medigraph.service.icu.application;

import com.medigraph.common.port.Outbox;
import com.medigraph.common.port.OutboxMessage;
import com.medigraph.common.port.UnitOfWork;
import com.medigraph.icu.stream.WindowScore;
import com.medigraph.service.icu.application.port.out.AlertRepository;
import com.medigraph.service.icu.domain.IcuAlert;

import java.time.Instant;
import java.util.List;
import java.util.logging.Logger;

/**
 * Stream listener: persists alerting windows and emits {@code News2AlertRaised} (paging/notification consumers
 * subscribe to it) in one transaction. A redelivered window is a no-op, so no duplicate page.
 */
public final class AlertRecorder {

    private static final Logger LOG = Logger.getLogger(AlertRecorder.class.getName());

    private final AlertRepository alerts;
    private final Outbox outbox;
    private final UnitOfWork tx;

    public AlertRecorder(AlertRepository alerts, Outbox outbox, UnitOfWork tx) {
        this.alerts = alerts;
        this.outbox = outbox;
        this.tx = tx;
    }

    public void onWindow(WindowScore w) {
        if (!w.alert() || !w.complete()) {
            return;
        }
        IcuAlert a = new IcuAlert(w.patientId(), Instant.ofEpochMilli(w.windowStart()), Instant.ofEpochMilli(w.windowEnd()),
                w.score().total(), w.score().risk(), w.alertReason());
        boolean fresh = tx.inTransaction(() -> {
            if (!alerts.saveIfAbsent(a)) {
                return false;
            }
            outbox.enqueue(new OutboxMessage(OutboxMessage.EVENTS, "IcuAlert", a.patientId() + "@" + w.windowEnd(),
                    "News2AlertRaised", "{\"healthId\":\"" + a.patientId() + "\",\"score\":" + a.score()
                            + ",\"risk\":\"" + a.risk() + "\",\"reason\":\"" + a.reason().replace("\"", "'")
                            + "\",\"windowEnd\":\"" + a.windowEnd() + "\"}"));
            return true;
        });
        if (fresh) {
            LOG.warning(() -> "NEWS2 alert raised windowEnd=" + a.windowEnd() + " score=" + a.score() + " risk=" + a.risk());
        }
    }

    public List<IcuAlert> recent(String canonicalId, int limit) {
        return alerts.recent(canonicalId, limit);
    }
}
