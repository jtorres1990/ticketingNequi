package com.nequi.paymentmock.domain;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable state of one payment attempt. Every transition is a pure function; the application applies it
 * atomically per {@code paymentAttemptId} (PM-SPK-005), so the transitions of an attempt are linearizable.
 *
 * <p>Authorization order (plan §4.2): payload conflict, stored result (replay), decision in progress (join),
 * registered cancellation ({@code ATTEMPT_CANCELLED}), applicable rule, decline percentage, default outcome.
 * Every authenticated and valid invocation is counted (PM-IV-010).
 *
 * @param payload           payload fixed by the first valid invocation, or null
 * @param transientProgress transient failures already returned per rule identifier (PM-IV-009)
 * @param decisionPending   a decision with latency is in progress (PM-IV-013)
 * @param result            stored result, or null
 * @param cancellation      registered cancellation, or null
 */
public record Attempt(String paymentAttemptId, AttemptPayload payload, long invocations,
        Map<String, Long> transientProgress, boolean decisionPending, AuthorizationResult result,
        Cancellation cancellation) {

    public Attempt {
        Objects.requireNonNull(paymentAttemptId, "paymentAttemptId");
        transientProgress = Map.copyOf(transientProgress);
    }

    public static Attempt empty(String paymentAttemptId) {
        return new Attempt(paymentAttemptId, null, 0, Map.of(), false, null, null);
    }

    public AuthorizationStep authorize(AttemptPayload requested, MockConfiguration configuration) {
        Objects.requireNonNull(requested, "requested");
        if (payload != null && !payload.equals(requested)) {
            return new AuthorizationStep.PayloadConflict(counted(payload));
        }
        Attempt counted = counted(requested);
        if (result != null) {
            return new AuthorizationStep.Replayed(counted, result);
        }
        if (decisionPending) {
            return new AuthorizationStep.JoinPending(counted);
        }
        if (cancellation != null) {
            return new AuthorizationStep.Decided(counted.withResult(AuthorizationResult.ATTEMPT_CANCELLED),
                    AuthorizationResult.ATTEMPT_CANCELLED);
        }
        return switch (OutcomeSelector.select(configuration, paymentAttemptId, requested)) {
            case OutcomeSelector.Decide decide ->
                    new AuthorizationStep.Decided(counted.withResult(decide.result()), decide.result());
            case OutcomeSelector.DefinitiveError ignored -> new AuthorizationStep.DefinitiveError(counted);
            case OutcomeSelector.TransientThenOutcome transientRule -> {
                long consumed = transientProgress.getOrDefault(transientRule.ruleId(), 0L);
                if (consumed < transientRule.transientFailures()) {
                    Map<String, Long> progress = new HashMap<>(transientProgress);
                    progress.put(transientRule.ruleId(), consumed + 1);
                    yield new AuthorizationStep.TransientFailure(new Attempt(paymentAttemptId, counted.payload,
                            counted.invocations, progress, false, null, cancellation));
                }
                yield new AuthorizationStep.Decided(counted.withResult(transientRule.finalResult()),
                        transientRule.finalResult());
            }
            case OutcomeSelector.Delay delay -> new AuthorizationStep.DecisionStarted(
                    new Attempt(paymentAttemptId, counted.payload, counted.invocations, transientProgress, true,
                            null, cancellation),
                    delay.addedLatencyMs(), delay.result());
        };
    }

    /**
     * Ends a decision with latency (PM-IV-013): a cancellation registered meanwhile wins and the result is
     * {@code ATTEMPT_CANCELLED} (ADR-030 rule 3); otherwise the planned result is stored.
     */
    public Attempt completePending(AuthorizationResult planned) {
        if (result != null || !decisionPending) {
            return this;
        }
        AuthorizationResult committed = cancellation != null ? AuthorizationResult.ATTEMPT_CANCELLED : planned;
        return new Attempt(paymentAttemptId, payload, invocations, transientProgress, false, committed, cancellation);
    }

    /**
     * Cancellation (FG-003, ADR-030 rules 2 and 3), valid whatever the state of the attempt. The first one fixes the
     * status: {@code REVERSED} after {@code APPROVED}, {@code VOIDED} after {@code DECLINED}, and
     * {@code REGISTERED_BEFORE_CHARGE} when no result exists (non-existent attempt, failures without result, or a
     * decision still in progress). Repetitions return the same status and only increase {@code received}.
     */
    public CancellationStep cancel(Instant receivedAt) {
        if (cancellation != null) {
            return new CancellationStep(withCancellation(cancellation.receivedAgain()), cancellation.status(), true);
        }
        CancellationStatus status = result == null ? CancellationStatus.REGISTERED_BEFORE_CHARGE
                : result.status() == Outcome.APPROVED ? CancellationStatus.REVERSED : CancellationStatus.VOIDED;
        Cancellation first = new Cancellation(status, 1, receivedAt, result != null);
        return new CancellationStep(withCancellation(first), status, false);
    }

    /** {@code cancelled} of the contract: the cancellation arrived after the result existed (PM-IV-011). */
    public boolean cancelledAfterResult() {
        return cancellation != null && cancellation.afterResult();
    }

    public boolean hasAuthorizations() {
        return invocations > 0;
    }

    private Attempt counted(AttemptPayload fixedPayload) {
        return new Attempt(paymentAttemptId, fixedPayload, invocations + 1, transientProgress, decisionPending, result,
                cancellation);
    }

    private Attempt withCancellation(Cancellation registered) {
        return new Attempt(paymentAttemptId, payload, invocations, transientProgress, decisionPending, result,
                registered);
    }

    private Attempt withResult(AuthorizationResult stored) {
        return new Attempt(paymentAttemptId, payload, invocations, transientProgress, false, stored, cancellation);
    }

    /** Never prints the customer reference (ADR-032). */
    @Override
    public String toString() {
        return "Attempt[id=" + paymentAttemptId + ", invocations=" + invocations + ", pending=" + decisionPending
                + ", result=" + result + ", cancellation=" + cancellation + "]";
    }
}
