package com.medigraph.platform.ledger;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** {@code medigraph.ledger.nodes}: base URLs of every ledger-service replica (the client finds the leader). */
@ConfigurationProperties("medigraph.ledger")
public record LedgerClientProperties(List<String> nodes) {
    public LedgerClientProperties {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }
}
