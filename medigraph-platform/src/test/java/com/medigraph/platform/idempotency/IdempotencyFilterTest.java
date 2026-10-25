package com.medigraph.platform.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyFilterTest {

    /** Same contract as the JDBC store, in memory. */
    static final class MapStore implements IdempotencyStore {
        final Map<String, Entry> rows = new ConcurrentHashMap<>();

        @Override
        public Optional<Entry> claim(String scope, String hash) {
            Entry prev = rows.putIfAbsent(scope, new Entry(hash, State.IN_PROGRESS, 0, null, null));
            return Optional.ofNullable(prev);
        }

        @Override
        public void complete(String scope, int status, String ct, byte[] body) {
            rows.computeIfPresent(scope, (k, e) -> new Entry(e.requestHash(), State.COMPLETED, status, ct, body));
        }

        @Override
        public void release(String scope) {
            rows.remove(scope);
        }
    }

    private final MapStore store = new MapStore();
    private final IdempotencyFilter filter = new IdempotencyFilter(store, new ObjectMapper());
    private final AtomicInteger executions = new AtomicInteger();
    private int nextStatus = 201;

    private final FilterChain chain = (req, res) -> {
        executions.incrementAndGet();
        String body = new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        HttpServletResponse r = (HttpServletResponse) res;
        r.setStatus(nextStatus);
        r.setContentType("application/json");
        r.getWriter().write("{\"echo\":" + body + ",\"n\":" + executions.get() + "}");
    };

    private MockHttpServletResponse post(String key, String body) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/records");
        req.setContent(body.getBytes(StandardCharsets.UTF_8));
        if (key != null) {
            req.addHeader(IdempotencyFilter.HEADER, key);
        }
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, chain);
        return res;
    }

    @Test
    void replaysTheFirstResponseForTheSameKeyAndBody() throws Exception {
        MockHttpServletResponse first = post("key-0000001", "{\"a\":1}");
        MockHttpServletResponse second = post("key-0000001", "{\"a\":1}");
        assertThat(executions).hasValue(1);
        assertThat(second.getStatus()).isEqualTo(201);
        assertThat(second.getContentAsString()).isEqualTo(first.getContentAsString()).contains("\"n\":1");
        assertThat(second.getHeader("Idempotent-Replayed")).isEqualTo("true");
    }

    @Test
    void sameKeyDifferentBodyIs422AndInProgressIs409() throws Exception {
        post("key-0000002", "{\"a\":1}");
        assertThat(post("key-0000002", "{\"a\":2}").getStatus()).isEqualTo(422);

        String hash = com.medigraph.common.crypto.Sha256.hex("{\"a\":3}".getBytes(StandardCharsets.UTF_8));
        store.rows.put("anonymous POST /api/v1/records key-0000003",
                new IdempotencyStore.Entry(hash, IdempotencyStore.State.IN_PROGRESS, 0, null, null));
        MockHttpServletResponse busy = post("key-0000003", "{\"a\":3}");
        assertThat(busy.getStatus()).isEqualTo(409);
        assertThat(busy.getHeader("Retry-After")).isEqualTo("1");
        assertThat(busy.getContentType()).isEqualTo("application/problem+json");
    }

    @Test
    void serverErrorsAreNotStoredSoTheClientCanRetry() throws Exception {
        nextStatus = 503;
        post("key-0000004", "{}");
        nextStatus = 201;
        MockHttpServletResponse retry = post("key-0000004", "{}");
        assertThat(executions).hasValue(2);
        assertThat(retry.getStatus()).isEqualTo(201);
    }

    @Test
    void requestsWithoutKeyOrReadsPassThroughAndBadKeysAre400() throws Exception {
        post(null, "{}");
        post(null, "{}");
        assertThat(executions).hasValue(2);
        assertThat(store.rows).isEmpty();
        assertThat(post("short", "{}").getStatus()).isEqualTo(400);
    }
}
