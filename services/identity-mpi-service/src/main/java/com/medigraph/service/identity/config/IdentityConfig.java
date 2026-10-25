package com.medigraph.service.identity.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medigraph.common.port.Outbox;
import com.medigraph.common.port.UnitOfWork;
import com.medigraph.mpi.MasterPatientIndex;
import com.medigraph.mpi.MatchConfig;
import com.medigraph.service.identity.adapter.out.persistence.JdbcPatientDirectory;
import com.medigraph.service.identity.adapter.out.persistence.JdbcReviewQueue;
import com.medigraph.service.identity.application.PatientLookup;
import com.medigraph.service.identity.application.PatientRegistration;
import com.medigraph.service.identity.application.StewardReview;
import com.medigraph.service.identity.application.port.out.PatientDirectory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class IdentityConfig {

    @Bean
    PatientDirectory patientDirectory(JdbcTemplate jdbc) {
        return new JdbcPatientDirectory(jdbc);
    }

    @Bean
    MasterPatientIndex masterPatientIndex(JdbcTemplate jdbc, ObjectMapper json) {
        return new MasterPatientIndex(MatchConfig.defaults(), new JdbcReviewQueue(jdbc, json), Clock.systemUTC());
    }

    @Bean
    PatientRegistration patientRegistration(MasterPatientIndex mpi, PatientDirectory d, UnitOfWork tx, Outbox outbox) {
        return new PatientRegistration(mpi, d, tx, outbox);
    }

    @Bean
    StewardReview stewardReview(MasterPatientIndex mpi, PatientDirectory d, UnitOfWork tx, Outbox outbox,
                                PatientRegistration registration) {
        return new StewardReview(mpi, d, tx, outbox, registration);
    }

    @Bean
    PatientLookup patientLookup(PatientDirectory d, MasterPatientIndex mpi, UnitOfWork tx, Outbox outbox,
                                PatientRegistration registration) {
        return new PatientLookup(d, mpi, tx, outbox, registration);
    }

    /** The MPI index is derived state: rebuild it before the service takes traffic. */
    @Bean
    ApplicationRunner restoreMpiIndex(PatientRegistration registration) {
        return args -> registration.restoreIndex();
    }
}
