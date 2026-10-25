package com.medigraph.service.records.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;

/**
 * KEK sources (see {@code KekProviders}):
 * <ul>
 *   <li>{@code env} (default): {@code MEDIGRAPH_KEK_<ID>} + {@code MEDIGRAPH_KEK_CURRENT}; injected by the orchestrator.</li>
 *   <li>{@code directory}: one base64 file per KEK id (e.g. a Kubernetes secret volume) + {@code current-id}.</li>
 *   <li>{@code inline}: a single base64 key in config. Local development and tests only.</li>
 * </ul>
 * There is no "generate if missing" for KEKs: a lost KEK means every record is unreadable, so a missing KEK is a
 * startup failure, never a silent new key.
 */
@ConfigurationProperties("medigraph.records")
public record RecordsProperties(@DefaultValue("http://localhost:8081") String identityUrl,
                                @DefaultValue("http://localhost:8083") String consentUrl,
                                @DefaultValue Kek kek) {

    public record Kek(@DefaultValue("env") String source, Path directory, String currentId, String inlineBase64) {
    }
}
