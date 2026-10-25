package com.medigraph.service.consent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medigraph.common.port.Outbox;
import com.medigraph.common.port.UnitOfWork;
import com.medigraph.consent.ConsentService;
import com.medigraph.consent.ConsentSigner;
import com.medigraph.platform.http.DownstreamFactory;
import com.medigraph.platform.security.ServiceTokenProvider;
import com.medigraph.platform.security.TokenSource;
import com.medigraph.service.consent.adapter.out.http.IdentityHttpResolver;
import com.medigraph.service.consent.adapter.out.keys.PemConsentKeyFile;
import com.medigraph.service.consent.adapter.out.ledger.OutboxLedgerAnchor;
import com.medigraph.service.consent.adapter.out.persistence.JdbcConsentStore;
import com.medigraph.service.consent.adapter.out.persistence.JdbcPatientNotifier;
import com.medigraph.service.consent.adapter.out.persistence.JdbcSigningKeyRegistry;
import com.medigraph.service.consent.application.AccessDecisions;
import com.medigraph.service.consent.application.AliasAwareConsentStore;
import com.medigraph.service.consent.application.CachingPatientResolver;
import com.medigraph.service.consent.application.ConsentCommands;
import com.medigraph.service.consent.application.OutboxConsentAudit;
import com.medigraph.service.consent.application.port.out.PatientResolver;
import com.medigraph.service.consent.application.port.out.SigningKeyRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ConsentProperties.class)
public class ConsentConfig {

    private static final Logger log = LoggerFactory.getLogger(ConsentConfig.class);

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    SigningKeyRegistry signingKeyRegistry(JdbcTemplate jdbc) {
        return new JdbcSigningKeyRegistry(jdbc);
    }

    /** Loads (or, in dev, creates) the PEM, registers its public half, and trusts every key registered before it. */
    @Bean
    ConsentSigner consentSigner(ConsentProperties p, SigningKeyRegistry registry) {
        PemConsentKeyFile.LoadedKey key = PemConsentKeyFile.loadOrCreate(p.signingKeyPath(), p.generateKeyIfMissing());
        if (key.generated()) {
            log.warn("generated a new consent signing key at {} (development mode)", p.signingKeyPath());
        }
        ConsentSigner signer = new ConsentSigner(key.id().value(), key.pair());
        registry.register(key.id().value(), key.pair().getPublic());
        registry.all().forEach((id, pub) -> {
            if (!id.equals(signer.keyId())) {
                signer.trust(id, pub);
            }
        });
        log.info("consent signing key {} active", signer.keyId());
        return signer;
    }

    @Bean
    PatientResolver patientResolver(DownstreamFactory http, ConsentProperties p, ServiceTokenProvider tokens) {
        return new CachingPatientResolver(
                new IdentityHttpResolver(http.create("identity", p.identityUrl(), TokenSource.service(tokens))),
                p.identityCacheTtl(), 10_000, Clock.systemUTC());
    }

    @Bean
    JdbcPatientNotifier patientNotifier(JdbcTemplate jdbc, Outbox outbox) {
        return new JdbcPatientNotifier(jdbc, outbox);
    }

    @Bean
    ConsentService consentService(JdbcTemplate jdbc, ConsentSigner signer, PatientResolver patients, Outbox outbox,
                                  ObjectMapper json, UnitOfWork tx, JdbcPatientNotifier notifier, ConsentProperties p,
                                  Clock clock) {
        return new ConsentService(new AliasAwareConsentStore(new JdbcConsentStore(jdbc), patients), signer,
                new OutboxConsentAudit(new OutboxLedgerAnchor(outbox, json), tx, clock), notifier, clock,
                p.breakGlassWindow(), p.maxConsentDuration());
    }

    @Bean
    ConsentCommands consentCommands(ConsentService consent, PatientResolver patients, JdbcPatientNotifier inbox,
                                    UnitOfWork tx) {
        return new ConsentCommands(consent, patients, inbox, tx);
    }

    @Bean
    AccessDecisions accessDecisions(ConsentService consent, PatientResolver patients) {
        return new AccessDecisions(consent, patients);
    }
}
