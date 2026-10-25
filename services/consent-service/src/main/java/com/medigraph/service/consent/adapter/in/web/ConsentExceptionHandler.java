package com.medigraph.service.consent.adapter.in.web;

import com.medigraph.consent.ConsentException;
import com.medigraph.platform.web.ProblemDetailsAdvice;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Consent rule violations (too-short break-glass reason, revoking someone else's consent, ...) are 422s. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class ConsentExceptionHandler {

    @ExceptionHandler(ConsentException.class)
    ResponseEntity<ProblemDetail> consentRule(ConsentException e) {
        return ProblemDetailsAdvice.problem(HttpStatus.UNPROCESSABLE_ENTITY, "consent-rule", e.getMessage());
    }
}
