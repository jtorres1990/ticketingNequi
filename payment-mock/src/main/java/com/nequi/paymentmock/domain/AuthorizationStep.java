package com.nequi.paymentmock.domain;

/** Outcome of one authorization invocation on the attempt state machine, with the next state to store. */
public sealed interface AuthorizationStep {

    Attempt next();

    /** A result already existed: returned with {@code replayed = true}. */
    record Replayed(Attempt next, AuthorizationResult result) implements AuthorizationStep {
    }

    /** A decision with latency is in progress: join it without evaluating rules again (PM-IV-010). */
    record JoinPending(Attempt next) implements AuthorizationStep {
    }

    /** Same {@code paymentAttemptId} with another payload: 422 {@code IDEMPOTENCY_KEY_REUSED}, nothing changes. */
    record PayloadConflict(Attempt next) implements AuthorizationStep {
    }

    /** This invocation committed the result ({@code replayed = false}). */
    record Decided(Attempt next, AuthorizationResult result) implements AuthorizationStep {
    }

    /** Simulated definitive error; no result stored (PM-IV-009). */
    record DefinitiveError(Attempt next) implements AuthorizationStep {
    }

    /** Simulated transient failure; one more failure consumed for (attempt, rule) (PM-IV-009). */
    record TransientFailure(Attempt next) implements AuthorizationStep {
    }

    /** This invocation started a decision with latency; the planned result is committed when it ends. */
    record DecisionStarted(Attempt next, int addedLatencyMs, AuthorizationResult planned) implements AuthorizationStep {
    }
}
