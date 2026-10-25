package com.medigraph.service.beds.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * @param holdStore      {@code jdbc} (default, same DB as the counters) or {@code redis} (Lua compare-and-set)
 * @param locator        {@code jdbc} (bounding box + haversine bounded heap) or {@code postgis} (GiST KNN)
 * @param expiryInterval how often expired holds are released; {@code 0s} disables the scheduler
 */
@ConfigurationProperties("medigraph.beds")
public record BedsProperties(@DefaultValue("jdbc") String holdStore,
                             @DefaultValue("jdbc") String locator,
                             @DefaultValue("15m") Duration defaultHoldTtl,
                             @DefaultValue("30s") Duration expiryInterval,
                             @DefaultValue({"beds-shard-1", "beds-shard-2", "beds-shard-3"}) List<String> shards,
                             @DefaultValue("128") int virtualNodes) {
}
