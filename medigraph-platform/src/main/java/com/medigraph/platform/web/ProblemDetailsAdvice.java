package com.medigraph.platform.web;

import com.medigraph.common.error.AccessDeniedException;
import com.medigraph.common.error.ConflictException;
import com.medigraph.common.error.NotFoundException;
import com.medigraph.common.error.UpstreamUnavailableException;
import com.medigraph.common.id.InvalidHealthIdException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;

/**
 * Maps domain/application exceptions to RFC 7807 {@code application/problem+json}. Messages of client errors are
 * shown (they are written for callers); server errors get a generic text plus the correlation id, never a stack
 * trace. Spring MVC's own exceptions (bad JSON, validation, 404 route...) are handled by the base class.
 * Services may add their own advice with a higher precedence for service-specific exceptions.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class ProblemDetailsAdvice extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsAdvice.class);

    public static ResponseEntity<ProblemDetail> problem(org.springframework.http.HttpStatusCode status, String slug, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setType(URI.create("https://medigraph.dev/problems/" + slug));
        String cid = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (cid != null) {
            p.setProperty("correlationId", cid);
        }
        return ResponseEntity.status(status).body(p);
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(NotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "not-found", e.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> denied(AccessDeniedException e) {
        return problem(HttpStatus.FORBIDDEN, "access-denied", e.getMessage());
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    ResponseEntity<ProblemDetail> roleDenied(org.springframework.security.access.AccessDeniedException e) {
        return problem(HttpStatus.FORBIDDEN, "forbidden", "your role is not allowed to perform this operation");
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ProblemDetail> conflict(ConflictException e) {
        return problem(HttpStatus.CONFLICT, "conflict", e.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, InvalidHealthIdException.class})
    ResponseEntity<ProblemDetail> badRequest(RuntimeException e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-request", e.getMessage());
    }

    /** Bean-validation on {@code @RequestParam}/{@code @PathVariable} of {@code @Validated} controllers. */
    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> constraint(jakarta.validation.ConstraintViolationException e) {
        String detail = e.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + " " + v.getMessage()).sorted().reduce((a, b) -> a + "; " + b)
                .orElse("invalid request");
        return problem(HttpStatus.BAD_REQUEST, "invalid-request", detail);
    }

    @ExceptionHandler({UpstreamUnavailableException.class, CallNotPermittedException.class})
    ResponseEntity<ProblemDetail> unavailable(RuntimeException e) {
        log.warn("dependency unavailable: {}", e.getMessage());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "dependency-unavailable", "a dependency is unavailable; retry later");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e) {
        log.error("unhandled error", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal", "internal error");
    }
}
