package com.medigraph.service.ledger.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medigraph.ledger.raft.RaftConfig;
import com.medigraph.platform.security.ServiceTokenProvider;
import com.medigraph.service.ledger.adapter.in.web.LedgerWebSettings;
import com.medigraph.service.ledger.adapter.in.web.NodeIdentity;
import com.medigraph.service.ledger.adapter.out.http.HttpRaftTransport;
import com.medigraph.service.ledger.adapter.out.persistence.JdbcBlockStore;
import com.medigraph.service.ledger.adapter.out.persistence.JdbcRaftStorage;
import com.medigraph.service.ledger.application.LedgerAuditQuery;
import com.medigraph.service.ledger.application.LedgerReplica;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Random;

/** Composition root: the only class that knows which adapter implements which port. */
@Configuration(proxyBeanMethods = false)
public class LedgerServiceConfig {

    /** Storage format is independent of the web ObjectMapper's settings. */
    private static final ObjectMapper STORAGE_JSON = new ObjectMapper();

    @Bean(destroyMethod = "close")
    HttpRaftTransport raftTransport(LedgerNodeProperties p, ObjectMapper json, ServiceTokenProvider tokens) {
        return new HttpRaftTransport(p.peers(), json, tokens, p.rpcTimeout());
    }

    @Bean
    LedgerReplica ledgerReplica(LedgerNodeProperties p, JdbcTemplate jdbc, TransactionTemplate tx,
                                HttpRaftTransport transport) {
        RaftConfig cfg = new RaftConfig(p.electionTicksMin(), p.electionTicksMax(), p.heartbeatTicks(), p.maxEntriesPerAppend());
        return new LedgerReplica(p.nodeId(), new ArrayList<>(p.peers().keySet()), cfg,
                new JdbcRaftStorage(jdbc, tx, STORAGE_JSON), new JdbcBlockStore(jdbc, tx, STORAGE_JSON), transport,
                Clock.systemUTC(), new Random(p.nodeId().hashCode() ^ System.nanoTime()));
    }

    @Bean
    RaftTicker raftTicker(LedgerReplica replica, LedgerNodeProperties p) {
        return new RaftTicker(replica, p.tickInterval());
    }

    @Bean
    LedgerAuditQuery ledgerAuditQuery(LedgerReplica replica) {
        return new LedgerAuditQuery(replica);
    }

    @Bean
    NodeIdentity nodeIdentity(LedgerNodeProperties p) {
        return new NodeIdentity(p.nodeId(), p.peers());
    }

    @Bean
    LedgerWebSettings ledgerWebSettings(LedgerNodeProperties p) {
        return new LedgerWebSettings(p.commitTimeout());
    }

    /** Readiness: DOWN after a fail-stop (the replica refuses work until restarted). */
    @Bean
    HealthIndicator raft(LedgerReplica replica) {
        return () -> replica.healthy()
                ? Health.up().withDetail("role", replica.status().role()).build()
                : Health.down().withDetail("reason", replica.failureReason()).build();
    }
}
