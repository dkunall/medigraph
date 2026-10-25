package com.medigraph.platform.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medigraph.common.port.Outbox;
import com.medigraph.common.port.UnitOfWork;
import com.medigraph.platform.idempotency.IdempotencyFilter;
import com.medigraph.platform.idempotency.IdempotencyStore;
import com.medigraph.platform.idempotency.JdbcIdempotencyStore;
import com.medigraph.platform.ledger.LedgerClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;

/**
 * Persistence-backed platform pieces, active only in services that have a database: UnitOfWork, the transactional
 * outbox and its relay (ledger sink when {@code medigraph.ledger.nodes} is set, log or Kafka sink for events),
 * and the Idempotency-Key filter.
 */
@AutoConfiguration(after = {JdbcTemplateAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class,
        TransactionAutoConfiguration.class, com.medigraph.platform.http.PlatformHttpAutoConfiguration.class})
@ConditionalOnBean({JdbcTemplate.class, TransactionTemplate.class})
@EnableConfigurationProperties(OutboxProperties.class)
public class PlatformPersistenceAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public UnitOfWork unitOfWork(TransactionTemplate tx) {
        return new SpringUnitOfWork(tx);
    }

    @Bean
    @ConditionalOnMissingBean
    public Outbox outbox(JdbcTemplate jdbc) {
        return new JdbcOutbox(jdbc);
    }

    @Bean
    @ConditionalOnMissingBean(name = "eventsOutboxSink")
    @ConditionalOnProperty(prefix = "medigraph.outbox", name = "events-sink", havingValue = "log", matchIfMissing = true)
    public OutboxSink eventsOutboxSink() {
        return new LoggingEventSink();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(name = "kafkaEventsOutboxSink")
    @ConditionalOnProperty(prefix = "medigraph.outbox", name = "events-sink", havingValue = "kafka")
    public KafkaEventSink kafkaEventsOutboxSink(OutboxProperties p) {
        return KafkaEventSink.create(p.kafkaBootstrapServers(), p.eventsTopic());
    }

    @Bean
    @ConditionalOnBean(LedgerClient.class)
    public OutboxSink ledgerOutboxSink(LedgerClient ledger, ObjectMapper json,
                                       @Value("${spring.application.name:service}") String service) {
        return new LedgerOutboxSink(ledger, json, service);
    }

    @Bean
    public OutboxRelay outboxRelay(JdbcTemplate jdbc, TransactionTemplate tx, List<OutboxSink> sinks,
                                   OutboxProperties p, ObjectProvider<MeterRegistry> meters) {
        return new OutboxRelay(jdbc, tx, sinks, p, meters.getIfAvailable(SimpleMeterRegistry::new));
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotencyStore idempotencyStore(JdbcTemplate jdbc) {
        return new JdbcIdempotencyStore(jdbc, Duration.ofSeconds(60));
    }

    @Bean
    public FilterRegistrationBean<IdempotencyFilter> idempotencyFilter(IdempotencyStore store, ObjectMapper json) {
        FilterRegistrationBean<IdempotencyFilter> r = new FilterRegistrationBean<>(new IdempotencyFilter(store, json));
        r.setOrder(SecurityProperties.DEFAULT_FILTER_ORDER + 10); // after authentication
        return r;
    }
}
