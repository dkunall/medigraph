package com.medigraph.service.icu.config;

import com.medigraph.common.port.Outbox;
import com.medigraph.common.port.UnitOfWork;
import com.medigraph.icu.kafka.KafkaStreamsVitalsProcessor;
import com.medigraph.icu.stream.InMemoryVitalsProcessor;
import com.medigraph.icu.stream.StreamConfig;
import com.medigraph.icu.stream.VitalsStreamProcessor;
import com.medigraph.platform.http.DownstreamFactory;
import com.medigraph.platform.security.ServiceTokenProvider;
import com.medigraph.platform.security.TokenSource;
import com.medigraph.service.icu.adapter.out.identity.IdentityHttpResolver;
import com.medigraph.service.icu.adapter.out.persistence.JdbcAlertRepository;
import com.medigraph.service.icu.application.AlertRecorder;
import com.medigraph.service.icu.application.VitalsIngestion;
import com.medigraph.service.icu.application.port.out.PatientResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class IcuConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean(destroyMethod = "close")
    VitalsStreamProcessor vitalsStreamProcessor(IcuProperties p) {
        StreamConfig cfg = StreamConfig.defaults();
        return switch (p.processor()) {
            case "in-memory" -> new InMemoryVitalsProcessor(cfg);
            case "kafka" -> {
                KafkaStreamsVitalsProcessor k = new KafkaStreamsVitalsProcessor(p.kafkaBootstrap(), "icu-news2", cfg);
                k.start();
                yield k;
            }
            default -> throw new IllegalStateException("medigraph.icu.processor must be kafka or in-memory");
        };
    }

    @Bean
    PatientResolver patientResolver(DownstreamFactory http, IcuProperties p, ServiceTokenProvider tokens) {
        return new IdentityHttpResolver(http.create("identity", p.identityUrl(), TokenSource.service(tokens)));
    }

    @Bean
    AlertRecorder alertRecorder(JdbcTemplate jdbc, Outbox outbox, UnitOfWork tx, VitalsStreamProcessor processor) {
        AlertRecorder recorder = new AlertRecorder(new JdbcAlertRepository(jdbc), outbox, tx);
        processor.subscribe(recorder::onWindow);
        return recorder;
    }

    @Bean
    VitalsIngestion vitalsIngestion(VitalsStreamProcessor processor, PatientResolver patients, Clock clock) {
        return new VitalsIngestion(processor, patients, clock);
    }

    @Bean
    WatermarkTicker watermarkTicker(VitalsIngestion ingestion, IcuProperties p, Clock clock) {
        return new WatermarkTicker(ingestion, p.watermarkInterval(), clock);
    }
}
