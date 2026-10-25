package com.medigraph.service.records.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medigraph.common.port.Outbox;
import com.medigraph.common.port.UnitOfWork;
import com.medigraph.crypto.KekProviders;
import com.medigraph.crypto.KeyEncryptionKeyProvider;
import com.medigraph.crypto.PatientKeyVault;
import com.medigraph.platform.http.DownstreamFactory;
import com.medigraph.platform.ledger.LedgerClient;
import com.medigraph.platform.security.ServiceTokenProvider;
import com.medigraph.platform.security.TokenSource;
import com.medigraph.service.records.adapter.out.codec.JacksonResourceCodec;
import com.medigraph.service.records.adapter.out.http.ConsentHttpPolicy;
import com.medigraph.service.records.adapter.out.http.IdentityHttpDirectory;
import com.medigraph.service.records.adapter.out.ledger.HttpIntegrityLedger;
import com.medigraph.service.records.adapter.out.ledger.OutboxLedgerAnchor;
import com.medigraph.service.records.adapter.out.persistence.JdbcHealthRecordRepository;
import com.medigraph.service.records.adapter.out.persistence.JdbcWrappedKeyStore;
import com.medigraph.service.records.application.KeyRotation;
import com.medigraph.service.records.application.RecordCommands;
import com.medigraph.service.records.application.RecordQueries;
import com.medigraph.service.records.application.port.out.AccessPolicy;
import com.medigraph.service.records.application.port.out.HealthRecordRepository;
import com.medigraph.service.records.application.port.out.PatientDirectory;
import com.medigraph.service.records.application.port.out.ResourceCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RecordsProperties.class)
public class RecordsConfig {

    private static final Logger log = LoggerFactory.getLogger(RecordsConfig.class);

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    KeyEncryptionKeyProvider kekProvider(RecordsProperties p) {
        RecordsProperties.Kek k = p.kek();
        KeyEncryptionKeyProvider kek = switch (k.source()) {
            case "env" -> KekProviders.fromEnvironment(System.getenv());
            case "directory" -> KekProviders.fromDirectory(k.directory(), k.currentId());
            case "inline" -> {
                log.warn("KEK loaded from inline configuration: acceptable for local development only");
                yield KekProviders.fromBase64(k.currentId(), k.inlineBase64());
            }
            default -> throw new IllegalStateException("medigraph.records.kek.source must be env|directory|inline");
        };
        log.info("KEK provider ready, current KEK id {}", kek.currentKekId()); // the id, never the key
        return kek;
    }

    @Bean
    JdbcWrappedKeyStore wrappedKeyStore(JdbcTemplate jdbc) {
        return new JdbcWrappedKeyStore(jdbc);
    }

    @Bean
    PatientKeyVault patientKeyVault(JdbcWrappedKeyStore store, KeyEncryptionKeyProvider kek) {
        return new PatientKeyVault(store, kek);
    }

    @Bean
    HealthRecordRepository healthRecordRepository(JdbcTemplate jdbc) {
        return new JdbcHealthRecordRepository(jdbc);
    }

    @Bean
    ResourceCodec resourceCodec(ObjectMapper json) {
        return new JacksonResourceCodec(json);
    }

    @Bean
    PatientDirectory patientDirectory(DownstreamFactory http, RecordsProperties p, ServiceTokenProvider tokens) {
        return new IdentityHttpDirectory(http.create("identity", p.identityUrl(), TokenSource.service(tokens)));
    }

    @Bean
    AccessPolicy accessPolicy(DownstreamFactory http, RecordsProperties p, ServiceTokenProvider tokens) {
        return new ConsentHttpPolicy(http.create("consent", p.consentUrl(), TokenSource.service(tokens)));
    }

    @Bean
    RecordCommands recordCommands(HealthRecordRepository records, PatientKeyVault vault, PatientDirectory patients,
                                  Outbox outbox, ObjectMapper json, ResourceCodec codec, UnitOfWork tx, Clock clock) {
        return new RecordCommands(records, vault, patients, new OutboxLedgerAnchor(outbox, json), codec, outbox, tx, clock);
    }

    @Bean
    RecordQueries recordQueries(HealthRecordRepository records, PatientKeyVault vault, PatientDirectory patients,
                                AccessPolicy policy, LedgerClient ledger, ObjectMapper json, ResourceCodec codec) {
        return new RecordQueries(records, vault, patients, policy, new HttpIntegrityLedger(ledger, json), codec);
    }

    @Bean
    KeyRotation keyRotation(JdbcWrappedKeyStore store, PatientKeyVault vault, KeyEncryptionKeyProvider kek) {
        return new KeyRotation(store, vault, kek);
    }
}
