package com.medigraph.service.prescription.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medigraph.common.port.Outbox;
import com.medigraph.common.port.UnitOfWork;
import com.medigraph.platform.http.Downstream;
import com.medigraph.platform.http.DownstreamFactory;
import com.medigraph.platform.security.TokenSource;
import com.medigraph.prescription.DrugCatalog;
import com.medigraph.prescription.OverrideAuditLog;
import com.medigraph.prescription.PrescriptionSafetyChecker;
import com.medigraph.prescription.PrescriptionService;
import com.medigraph.service.prescription.adapter.out.ledger.OutboxLedgerAnchor;
import com.medigraph.service.prescription.adapter.out.persistence.JdbcOverrideAuditLog;
import com.medigraph.service.prescription.adapter.out.persistence.JdbcPrescriptionRepository;
import com.medigraph.service.prescription.adapter.out.records.RecordsHttpChart;
import com.medigraph.service.prescription.adapter.out.records.RecordsHttpOrders;
import com.medigraph.service.prescription.application.DrugLookup;
import com.medigraph.service.prescription.application.PrescribingService;
import com.medigraph.service.prescription.application.port.out.PrescriptionRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class PrescriptionConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    DrugCatalog drugCatalog() {
        return DrugCatalog.loadSample();
    }

    @Bean
    DrugLookup drugLookup(DrugCatalog catalog) {
        return new DrugLookup(catalog);
    }

    @Bean
    OverrideAuditLog overrideAuditLog(JdbcTemplate jdbc, ObjectMapper json) {
        return new JdbcOverrideAuditLog(jdbc, json);
    }

    @Bean
    PrescriptionRepository prescriptionRepository(JdbcTemplate jdbc, Clock clock) {
        return new JdbcPrescriptionRepository(jdbc, clock);
    }

    /** Relays the prescriber's token: records-service applies consent to the doctor, not to this service. */
    @Bean
    Downstream recordsDownstream(DownstreamFactory http, PrescriptionProperties p) {
        return http.create("records", p.recordsUrl(), TokenSource.relayCaller());
    }

    @Bean
    PrescribingService prescribingService(DrugCatalog catalog, OverrideAuditLog audit, PrescriptionRepository repo,
                                          Downstream recordsDownstream, Outbox outbox, UnitOfWork tx, ObjectMapper json,
                                          Clock clock) {
        return new PrescribingService(new RecordsHttpChart(recordsDownstream, json),
                new PrescriptionService(new PrescriptionSafetyChecker(catalog), audit, clock), repo,
                new RecordsHttpOrders(recordsDownstream), new OutboxLedgerAnchor(outbox, json), outbox, tx, clock);
    }
}
