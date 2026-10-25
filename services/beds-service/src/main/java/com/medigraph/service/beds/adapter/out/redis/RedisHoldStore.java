package com.medigraph.service.beds.adapter.out.redis;

import com.medigraph.beds.BedHold;
import com.medigraph.beds.HoldStore;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Redis adapter for bed holds: one hash per hold plus a ZSET of active holds scored by expiry. Status transitions are
 * a Lua compare-and-set so they are atomic on the server. Hold hashes get a Redis TTL (expiry + 1 day) purely for
 * cleanup; the bed counter is released by {@code BedService.expireHolds()} reading the ZSET.
 * <p>Exercised by {@code RedisHoldStoreContainerTest} (Testcontainers, skipped when Docker is absent); JDBC is the default.
 */
public class RedisHoldStore implements HoldStore {

    private static final String ACTIVE = "medigraph:holds:active";
    private static final DefaultRedisScript<Long> CAS = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[1], 'status') == ARGV[1] then
              redis.call('HSET', KEYS[1], 'status', ARGV[2])
              if ARGV[2] ~= 'ACTIVE' then redis.call('ZREM', KEYS[2], ARGV[3]) end
              return 1
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;

    public RedisHoldStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private static String key(String holdId) {
        return "medigraph:hold:" + holdId;
    }

    @Override
    public void save(BedHold h) {
        redis.opsForHash().putAll(key(h.holdId()), Map.of(
                "hospitalId", h.hospitalId(), "patientId", h.patientId(),
                "createdAt", Long.toString(h.createdAt().toEpochMilli()),
                "expiresAt", Long.toString(h.expiresAt().toEpochMilli()), "status", h.status().name()));
        redis.expire(key(h.holdId()), Duration.between(Instant.now(), h.expiresAt()).plusDays(1));
        if (h.status() == BedHold.Status.ACTIVE) {
            redis.opsForZSet().add(ACTIVE, h.holdId(), h.expiresAt().toEpochMilli());
        }
    }

    @Override
    public Optional<BedHold> find(String holdId) {
        Map<Object, Object> m = redis.opsForHash().entries(key(holdId));
        if (m.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new BedHold(holdId, (String) m.get("hospitalId"), (String) m.get("patientId"),
                Instant.ofEpochMilli(Long.parseLong((String) m.get("createdAt"))),
                Instant.ofEpochMilli(Long.parseLong((String) m.get("expiresAt"))),
                BedHold.Status.valueOf((String) m.get("status"))));
    }

    @Override
    public boolean transition(String holdId, BedHold.Status from, BedHold.Status to) {
        Long r = redis.execute(CAS, List.of(key(holdId), ACTIVE), from.name(), to.name(), holdId);
        return r != null && r == 1L;
    }

    @Override
    public List<BedHold> activeExpiredAt(Instant now) {
        Set<String> ids = redis.opsForZSet().rangeByScore(ACTIVE, Double.NEGATIVE_INFINITY, now.toEpochMilli());
        List<BedHold> out = new ArrayList<>();
        if (ids != null) {
            for (String id : ids) {
                find(id).filter(h -> h.status() == BedHold.Status.ACTIVE).ifPresent(out::add);
            }
        }
        return out;
    }
}
