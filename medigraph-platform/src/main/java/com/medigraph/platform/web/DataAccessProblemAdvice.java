package com.medigraph.platform.web;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps data-access exceptions to RFC 7807. Separate from {@link ProblemDetailsAdvice} so services without
 * spring-jdbc (the gateway) do not need spring-dao on the classpath; ordered before the catch-all advice.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE - 1)
public class DataAccessProblemAdvice {

    /** A unique constraint is the last line of defence against duplicates (e.g. same facility MRN twice). */
    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<ProblemDetail> duplicate(DuplicateKeyException e) {
        return ProblemDetailsAdvice.problem(HttpStatus.CONFLICT, "conflict", "a resource with the same unique key already exists");
    }
}
