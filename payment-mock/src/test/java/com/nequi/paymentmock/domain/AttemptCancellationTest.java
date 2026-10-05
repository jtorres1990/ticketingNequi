package com.nequi.paymentmock.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Cancellation transitions (FG-003, ADR-030 rules 2 and 3, AC-035, PM-IV-011, PM-IV-013). */
class AttemptCancellationTest {

    static final AttemptPayload PAYLOAD = AttemptPayload.of("order-1", "event-1", "customer-1", List.of("T1"));
    static final Instant T0 = Instant.parse("2026-10-04T10:00:00Z");
    static final Instant T1 = Instant.parse("2026-10-04T10:00:05Z");
    static final MockConfiguration DECLINE_ALL = MockConfiguration.INITIAL.withDefaults(new Defaults(Outcome.DECLINED, 0));

    static Attempt approved() {
        return Attempt.empty("a-1").authorize(PAYLOAD, MockConfiguration.INITIAL).next();
    }

    @Test
    void approvedIsReversedAndRepetitionsKeepTheStatus() {
        CancellationStep first = approved().cancel(T0);
        assertThat(first.status()).isEqualTo(CancellationStatus.REVERSED);
        assertThat(first.replayed()).isFalse();
        assertThat(first.next().cancellation()).isEqualTo(new Cancellation(CancellationStatus.REVERSED, 1, T0, true));
        assertThat(first.next().cancelledAfterResult()).isTrue();

        CancellationStep second = first.next().cancel(T1);
        assertThat(second.status()).isEqualTo(CancellationStatus.REVERSED);
        assertThat(second.replayed()).isTrue();
        assertThat(second.next().cancellation()).isEqualTo(new Cancellation(CancellationStatus.REVERSED, 2, T0, true));
        assertThat(second.next().result()).isEqualTo(AuthorizationResult.APPROVED);
    }

    @Test
    void declinedIsVoided() {
        Attempt declined = Attempt.empty("a-1").authorize(PAYLOAD, DECLINE_ALL).next();
        CancellationStep step = declined.cancel(T0);
        assertThat(step.status()).isEqualTo(CancellationStatus.VOIDED);
        assertThat(step.next().cancelledAfterResult()).isTrue();
    }

    @Test
    void earlyCancellationMakesEveryLaterAuthorizationDeclineWithAttemptCancelled() {
        CancellationStep early = Attempt.empty("a-1").cancel(T0);
        assertThat(early.status()).isEqualTo(CancellationStatus.REGISTERED_BEFORE_CHARGE);
        assertThat(early.next().hasAuthorizations()).isFalse();

        MockConfiguration approveRule = MockConfiguration.INITIAL.withRules(RuleSet.EMPTY.add(
                OutcomeRule.create(1, MatchField.ORDER_ID, "order-1", Behaviour.approve())));
        AuthorizationStep first = early.next().authorize(PAYLOAD, approveRule);
        assertThat(first).isEqualTo(new AuthorizationStep.Decided(first.next(), AuthorizationResult.ATTEMPT_CANCELLED));
        assertThat(first.next().cancelledAfterResult()).isFalse();

        AuthorizationStep later = first.next().authorize(PAYLOAD, approveRule);
        assertThat(later).isEqualTo(new AuthorizationStep.Replayed(later.next(), AuthorizationResult.ATTEMPT_CANCELLED));
        assertThat(later.next().cancel(T1).status()).isEqualTo(CancellationStatus.REGISTERED_BEFORE_CHARGE);
        assertThat(later.next().cancel(T1).replayed()).isTrue();
    }

    @Test
    void cancellationDuringAPendingDecisionWinsWhenTheDecisionCompletes() {
        MockConfiguration latency = MockConfiguration.INITIAL.withRules(RuleSet.EMPTY.add(
                OutcomeRule.create(1, MatchField.ORDER_ID, "order-1", Behaviour.latency(500, Outcome.APPROVED, null))));
        AuthorizationStep started = Attempt.empty("a-1").authorize(PAYLOAD, latency);
        assertThat(started).isEqualTo(new AuthorizationStep.DecisionStarted(started.next(), 500, AuthorizationResult.APPROVED));
        assertThat(started.next().decisionPending()).isTrue();

        AuthorizationStep joined = started.next().authorize(PAYLOAD, latency);
        assertThat(joined).isInstanceOf(AuthorizationStep.JoinPending.class);
        assertThat(joined.next().invocations()).isEqualTo(2);

        CancellationStep cancelled = joined.next().cancel(T0);
        assertThat(cancelled.status()).isEqualTo(CancellationStatus.REGISTERED_BEFORE_CHARGE);

        Attempt completed = cancelled.next().completePending(AuthorizationResult.APPROVED);
        assertThat(completed.result()).isEqualTo(AuthorizationResult.ATTEMPT_CANCELLED);
        assertThat(completed.decisionPending()).isFalse();
        assertThat(completed.cancelledAfterResult()).isFalse();
        assertThat(completed.completePending(AuthorizationResult.APPROVED)).isSameAs(completed);
    }

    @Test
    void pendingDecisionWithoutCancellationStoresThePlannedResult() {
        MockConfiguration latency = MockConfiguration.INITIAL.withRules(RuleSet.EMPTY.add(
                OutcomeRule.create(1, MatchField.ORDER_ID, "order-1", Behaviour.latency(5, Outcome.DECLINED, null))));
        Attempt pending = Attempt.empty("a-1").authorize(PAYLOAD, latency).next();
        Attempt completed = pending.completePending(AuthorizationResult.declined(ReasonCode.RULE_DECLINED));
        assertThat(completed.result()).isEqualTo(AuthorizationResult.declined(ReasonCode.RULE_DECLINED));
        assertThat(completed.cancel(T0).status()).isEqualTo(CancellationStatus.VOIDED);
        assertThat(Attempt.empty("x").completePending(AuthorizationResult.APPROVED).result()).isNull();
    }
}
