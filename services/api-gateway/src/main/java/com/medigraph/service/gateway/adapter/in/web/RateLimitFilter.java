package com.medigraph.service.gateway.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medigraph.service.gateway.application.RateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;

/**
 * Token-bucket rate limiting at the edge, answered with RFC 7807 {@code 429} and {@code Retry-After}. Runs after
 * authentication, so an authenticated caller is limited per JWT subject (NAT-friendly) and anonymous calls (the
 * token endpoint) per peer address. Probes and docs are not limited.
 */
public final class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter limiter;
    private final ObjectMapper json;

    public RateLimitFilter(RateLimiter limiter, ObjectMapper json) {
        this.limiter = limiter;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String p = req.getRequestURI();
        return !(p.startsWith("/api/") || p.startsWith("/oauth2/") || p.startsWith("/dev/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        RateLimiter.Decision d = limiter.check(clientKey(req));
        if (d.allowed()) {
            chain.doFilter(req, res);
            return;
        }
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, "rate limit exceeded");
        pd.setType(URI.create("https://medigraph.example/problems/rate-limited"));
        pd.setTitle("Too Many Requests");
        res.setStatus(429);
        res.setHeader("Retry-After", Long.toString(d.retryAfterSeconds()));
        res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(res.getOutputStream(), pd);
    }

    private static String clientKey(HttpServletRequest req) {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a instanceof JwtAuthenticationToken jwt && jwt.isAuthenticated()) {
            return "sub:" + jwt.getName();
        }
        return "ip:" + req.getRemoteAddr();
    }
}
