package com.medigraph.service.ledger.adapter.in.web;

import com.medigraph.ledger.raft.NotLeaderException;
import com.medigraph.platform.web.ProblemDetailsAdvice;
import com.medigraph.service.ledger.application.CommitTimeoutException;
import com.medigraph.service.ledger.application.ReplicaFailedException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Raft-specific errors. A follower answers 421 Misdirected Request with {@code X-Raft-Leader} (id) and
 * {@code X-Raft-Leader-Url} when known: clients move to another node immediately instead of retrying this one,
 * and the response does not count as a failure for circuit breakers (it is not a 5xx).
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class LedgerExceptionHandler {

    private static final org.springframework.http.HttpStatusCode MISDIRECTED = org.springframework.http.HttpStatusCode.valueOf(421);

    private final NodeIdentity identity;

    public LedgerExceptionHandler(NodeIdentity identity) {
        this.identity = identity;
    }

    @ExceptionHandler(NotLeaderException.class)
    public ResponseEntity<ProblemDetail> notLeader(NotLeaderException e) {
        ResponseEntity.BodyBuilder b = ResponseEntity.status(MISDIRECTED);
        if (e.leaderHint() != null) {
            b.header("X-Raft-Leader", e.leaderHint());
            String url = identity.peerUrls().get(e.leaderHint());
            if (url != null) {
                b.header("X-Raft-Leader-Url", url);
            }
        }
        return b.body(ProblemDetailsAdvice.problem(MISDIRECTED, "not-raft-leader", e.getMessage()).getBody());
    }

    @ExceptionHandler(CommitTimeoutException.class)
    public ResponseEntity<ProblemDetail> timeout(CommitTimeoutException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "1")
                .body(ProblemDetailsAdvice.problem(HttpStatus.SERVICE_UNAVAILABLE, "ledger-commit-timeout", e.getMessage()).getBody());
    }

    @ExceptionHandler(ReplicaFailedException.class)
    public ResponseEntity<ProblemDetail> failed(ReplicaFailedException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ProblemDetailsAdvice.problem(HttpStatus.SERVICE_UNAVAILABLE, "ledger-replica-failed", e.getMessage()).getBody());
    }
}
