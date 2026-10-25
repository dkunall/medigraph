package com.medigraph.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.net.URI;

/** 401/403 from the security filter chain as RFC 7807 bodies (same shape as controller errors). */
public final class ProblemAuthHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper json;

    public ProblemAuthHandlers(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public void commence(HttpServletRequest req, HttpServletResponse res, AuthenticationException e) throws IOException {
        res.setHeader("WWW-Authenticate", "Bearer realm=\"medigraph\"");
        write(req, res, HttpStatus.UNAUTHORIZED, "missing, expired or invalid bearer token");
    }

    @Override
    public void handle(HttpServletRequest req, HttpServletResponse res, AccessDeniedException e) throws IOException {
        write(req, res, HttpStatus.FORBIDDEN, "your role is not allowed to perform this operation");
    }

    private void write(HttpServletRequest req, HttpServletResponse res, HttpStatus status, String detail) throws IOException {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setType(URI.create("https://medigraph.dev/problems/" + (status == HttpStatus.UNAUTHORIZED ? "unauthenticated" : "forbidden")));
        p.setInstance(URI.create(req.getRequestURI()));
        String cid = MDC.get("correlationId");
        if (cid != null) {
            p.setProperty("correlationId", cid);
        }
        res.setStatus(status.value());
        res.setContentType("application/problem+json");
        json.writeValue(res.getOutputStream(), p);
    }
}
