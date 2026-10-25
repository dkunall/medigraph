package com.medigraph.service.records.adapter.in.web;

import com.medigraph.crypto.KeyShreddedException;
import com.medigraph.platform.web.ProblemDetailsAdvice;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class RecordsExceptionHandler {

    /** 410 Gone: the data existed and was deliberately, permanently destroyed. */
    @ExceptionHandler(KeyShreddedException.class)
    public ResponseEntity<ProblemDetail> shredded(KeyShreddedException e) {
        return ProblemDetailsAdvice.problem(HttpStatus.GONE, "crypto-shredded",
                "this patient's data keys were destroyed at their request; the data is permanently unreadable");
    }
}
