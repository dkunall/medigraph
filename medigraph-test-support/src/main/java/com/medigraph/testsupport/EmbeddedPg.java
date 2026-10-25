package com.medigraph.testsupport;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * One real PostgreSQL 17 server per test JVM (zonky embedded binaries, started as a child process). No Docker and
 * no system database needed; each service's Flyway migrations create and fill its own schema, which mirrors the
 * schema-per-service layout used in docker-compose (there: one database per service).
 */
public final class EmbeddedPg {

    private static EmbeddedPostgres pg;

    private EmbeddedPg() {
    }

    public static synchronized int port() {
        if (pg == null) {
            try {
                pg = EmbeddedPostgres.builder().start();
            } catch (IOException e) {
                throw new UncheckedIOException("embedded PostgreSQL failed to start", e);
            }
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    pg.close();
                } catch (IOException ignored) {
                    // best effort
                }
            }));
        }
        return pg.getPort();
    }

    public static String url() {
        return "jdbc:postgresql://localhost:" + port() + "/postgres";
    }

    /** JDBC URL whose unqualified table names resolve to {@code schema}. */
    public static String url(String schema) {
        return url() + "?currentSchema=" + schema;
    }

    public static String username() {
        return "postgres";
    }

    public static String password() {
        return "postgres";
    }
}
