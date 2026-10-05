package com.nequi.paymentmock.domain;

import java.util.Optional;

/**
 * Deterministic outcome selection of ADR-030 and AV-004, evaluated on a configuration snapshot:
 * <ol>
 *   <li>the first applicable rule in effective order (orderId, ticketId, customerRef, eventId; most recent first);</li>
 *   <li>otherwise the decline percentage: {@code DECLINED/PERCENTAGE_DECLINED} when the stable bucket is below it;</li>
 *   <li>otherwise the default outcome ({@code DECLINED} uses {@code RULE_DECLINED}, PM-IV-007).</li>
 * </ol>
 */
public final class OutcomeSelector {

    private OutcomeSelector() {
    }

    /** What the selected configuration asks for. */
    public sealed interface Plan {
    }

    /** Decide now with this result. */
    public record Decide(AuthorizationResult result) implements Plan {
    }

    /** Simulated definitive error on every invocation (PM-IV-009). */
    public record DefinitiveError(String ruleId) implements Plan {
    }

    /** N transient failures per (attempt, rule), then the final result (PM-IV-009). */
    public record TransientThenOutcome(String ruleId, long transientFailures, AuthorizationResult finalResult)
            implements Plan {
    }

    /** Decide after the added latency; the result is fixed from the snapshot taken on arrival (PM-IV-013). */
    public record Delay(int addedLatencyMs, AuthorizationResult result) implements Plan {
    }

    public static Plan select(MockConfiguration configuration, String paymentAttemptId, AttemptPayload payload) {
        Optional<OutcomeRule> rule = configuration.rules().select(payload);
        if (rule.isEmpty()) {
            return new Decide(fallback(configuration.defaults(), paymentAttemptId));
        }
        OutcomeRule applicable = rule.get();
        Behaviour behaviour = applicable.behaviour();
        return switch (behaviour.type()) {
            case APPROVE -> new Decide(AuthorizationResult.APPROVED);
            case DECLINE -> new Decide(AuthorizationResult.declined(behaviour.declineReason()));
            case DEFINITIVE_ERROR -> new DefinitiveError(applicable.ruleId());
            case TRANSIENT_THEN_OUTCOME -> new TransientThenOutcome(applicable.ruleId(), behaviour.transientFailures(),
                    finalResult(behaviour));
            case LATENCY -> new Delay(behaviour.addedLatencyMs(), behaviour.finalOutcome() == null
                    ? fallback(configuration.defaults(), paymentAttemptId)
                    : finalResult(behaviour));
        };
    }

    /** Decline percentage, then default outcome. */
    public static AuthorizationResult fallback(Defaults defaults, String paymentAttemptId) {
        if (StableHash.declineBucket(paymentAttemptId) < defaults.declinePercentage()) {
            return AuthorizationResult.declined(ReasonCode.PERCENTAGE_DECLINED);
        }
        return defaults.defaultOutcome() == Outcome.APPROVED
                ? AuthorizationResult.APPROVED
                : AuthorizationResult.declined(ReasonCode.DEFAULT_DECLINE);
    }

    private static AuthorizationResult finalResult(Behaviour behaviour) {
        return behaviour.finalOutcome() == Outcome.APPROVED
                ? AuthorizationResult.APPROVED
                : AuthorizationResult.declined(behaviour.declineReason());
    }
}
