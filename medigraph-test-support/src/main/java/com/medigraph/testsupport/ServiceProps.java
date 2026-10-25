package com.medigraph.testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;

/** Points a service under test at its own schema on the shared embedded PostgreSQL and at the test token key. */
public final class ServiceProps {

    private ServiceProps() {
    }

    public static void register(DynamicPropertyRegistry r, String schema) {
        r.add("spring.datasource.url", () -> EmbeddedPg.url(schema));
        r.add("spring.datasource.username", EmbeddedPg::username);
        r.add("spring.datasource.password", EmbeddedPg::password);
        r.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
        r.add("spring.flyway.schemas", () -> schema);
        r.add("spring.flyway.default-schema", () -> schema);
        r.add("medigraph.security.jwt.public-key-pem", TestTokens::publicKeyPem);
        r.add("medigraph.outbox.poll-interval", () -> "100ms");
    }
}
