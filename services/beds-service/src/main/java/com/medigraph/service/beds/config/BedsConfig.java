package com.medigraph.service.beds.config;

import com.medigraph.beds.BedRepository;
import com.medigraph.beds.BedService;
import com.medigraph.beds.HoldStore;
import com.medigraph.beds.HospitalLocator;
import com.medigraph.beds.shard.ConsistentHashRing;
import com.medigraph.common.port.Outbox;
import com.medigraph.common.port.UnitOfWork;
import com.medigraph.service.beds.adapter.out.persistence.JdbcBedRepository;
import com.medigraph.service.beds.adapter.out.persistence.JdbcHaversineLocator;
import com.medigraph.service.beds.adapter.out.persistence.JdbcHoldStore;
import com.medigraph.service.beds.adapter.out.persistence.JdbcHospitalDirectory;
import com.medigraph.service.beds.adapter.out.postgis.PostgisHospitalLocator;
import com.medigraph.service.beds.adapter.out.redis.RedisHoldStore;
import com.medigraph.service.beds.application.BedAllocation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class BedsConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    BedRepository bedRepository(JdbcTemplate jdbc) {
        return new JdbcBedRepository(jdbc);
    }

    @Bean
    HoldStore holdStore(BedsProperties p, JdbcTemplate jdbc, ObjectProvider<StringRedisTemplate> redis) {
        return switch (p.holdStore()) {
            case "jdbc" -> new JdbcHoldStore(jdbc);
            case "redis" -> new RedisHoldStore(redis.getObject());
            default -> throw new IllegalStateException("medigraph.beds.hold-store must be jdbc or redis");
        };
    }

    @Bean
    HospitalLocator hospitalLocator(BedsProperties p, JdbcTemplate jdbc) {
        return switch (p.locator()) {
            case "jdbc" -> new JdbcHaversineLocator(jdbc);
            case "postgis" -> new PostgisHospitalLocator(jdbc);
            default -> throw new IllegalStateException("medigraph.beds.locator must be jdbc or postgis");
        };
    }

    @Bean
    ConsistentHashRing<String> shardRing(BedsProperties p) {
        ConsistentHashRing<String> ring = new ConsistentHashRing<>(p.virtualNodes());
        p.shards().forEach(ring::addNode);
        return ring;
    }

    @Bean
    BedAllocation bedAllocation(BedRepository beds, HoldStore holds, JdbcTemplate jdbc, HospitalLocator locator,
                                ConsistentHashRing<String> ring, Outbox outbox, UnitOfWork tx, Clock clock,
                                BedsProperties p) {
        return new BedAllocation(new BedService(beds, holds, clock), beds, new JdbcHospitalDirectory(jdbc), locator,
                ring, outbox, tx, p.defaultHoldTtl());
    }

    @Bean
    HoldExpiryTicker holdExpiryTicker(BedAllocation beds, BedsProperties p) {
        return new HoldExpiryTicker(beds, p.expiryInterval());
    }
}
