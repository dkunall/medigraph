package com.medigraph.platform.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medigraph.common.crypto.Sha256;
import com.medigraph.platform.web.ProblemDetailsAdvice;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Idempotency keys for write APIs (POST/PUT/PATCH/DELETE carrying {@code Idempotency-Key}), in the spirit of the
 * IETF httpapi idempotency-key draft:
 * <ul>
 *   <li>first request: executed; its status/body are stored (server errors are not stored, so they can be retried);</li>
 *   <li>same key + same body again: the stored response is replayed with {@code Idempotent-Replayed: true};</li>
 *   <li>same key + different body: 422;</li>
 *   <li>same key while the first is still running: 409 with {@code Retry-After}.</li>
 * </ul>
 * Keys are scoped by the authenticated caller, method and path, so two users cannot collide or read each other's
 * replays. Runs after the security filter chain (it needs the principal).
 */
public final class IdempotencyFilter extends OncePerRequestFilter {

    public static final String HEADER = "Idempotency-Key";
    private static final Set<String> WRITES = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{8,128}");

    private final IdempotencyStore store;
    private final ObjectMapper json;

    public IdempotencyFilter(IdempotencyStore store, ObjectMapper json) {
        this.store = store;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return !WRITES.contains(req.getMethod()) || req.getHeader(HEADER) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String key = req.getHeader(HEADER);
        if (!KEY.matcher(key).matches()) {
            problem(res, HttpStatus.BAD_REQUEST, "invalid-idempotency-key", "Idempotency-Key must match " + KEY.pattern());
            return;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String caller = auth == null ? "anonymous" : auth.getName();
        String scope = caller + ' ' + req.getMethod() + ' ' + req.getRequestURI() + ' ' + key;
        CachedBodyRequest wrapped = new CachedBodyRequest(req);
        String hash = Sha256.hex(wrapped.body());

        Optional<IdempotencyStore.Entry> existing = store.claim(scope, hash);
        if (existing.isPresent()) {
            IdempotencyStore.Entry e = existing.get();
            if (!e.requestHash().equals(hash)) {
                problem(res, HttpStatus.UNPROCESSABLE_ENTITY, "idempotency-key-reused",
                        "this Idempotency-Key was already used with a different request body");
            } else if (e.state() == IdempotencyStore.State.IN_PROGRESS) {
                res.setHeader("Retry-After", "1");
                problem(res, HttpStatus.CONFLICT, "idempotency-in-progress", "a request with this Idempotency-Key is still being processed");
            } else {
                res.setStatus(e.status());
                if (e.contentType() != null) {
                    res.setContentType(e.contentType());
                }
                res.setHeader("Idempotent-Replayed", "true");
                if (e.body() != null) {
                    res.getOutputStream().write(e.body());
                }
            }
            return;
        }

        ContentCachingResponseWrapper out = new ContentCachingResponseWrapper(res);
        boolean stored = false;
        try {
            chain.doFilter(wrapped, out);
            if (out.getStatus() < 500) {
                store.complete(scope, out.getStatus(), out.getContentType(), out.getContentAsByteArray());
                stored = true;
            }
        } finally {
            if (!stored) {
                store.release(scope);
            }
            out.copyBodyToResponse();
        }
    }

    private void problem(HttpServletResponse res, HttpStatus status, String slug, String detail) throws IOException {
        ProblemDetail p = ProblemDetailsAdvice.problem(status, slug, detail).getBody();
        res.setStatus(status.value());
        res.setContentType("application/problem+json");
        json.writeValue(res.getOutputStream(), p);
    }
}
