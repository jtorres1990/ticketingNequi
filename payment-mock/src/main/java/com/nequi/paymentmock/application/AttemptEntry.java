package com.nequi.paymentmock.application;

import com.nequi.paymentmock.domain.Attempt;
import com.nequi.paymentmock.domain.AuthorizationResult;
import java.util.Objects;
import reactor.core.publisher.Mono;

/**
 * Stored value per {@code paymentAttemptId}: the immutable domain state plus, while a decision with latency is in
 * progress, the shared decision that later invocations join (PM-IV-010, PM-IV-013).
 */
record AttemptEntry(Attempt attempt, Mono<AuthorizationResult> pendingDecision) {

    AttemptEntry {
        Objects.requireNonNull(attempt, "attempt");
    }

    static AttemptEntry empty(String paymentAttemptId) {
        return new AttemptEntry(Attempt.empty(paymentAttemptId), null);
    }
}
