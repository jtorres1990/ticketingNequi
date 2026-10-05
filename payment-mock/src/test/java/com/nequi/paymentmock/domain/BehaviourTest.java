package com.nequi.paymentmock.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** PM-IV-007: strict validation per behaviour type and configurable reason codes. */
class BehaviourTest {

    @Test
    void acceptsEveryValidCombination() {
        assertThat(Behaviour.approve().type()).isEqualTo(BehaviourType.APPROVE);
        assertThat(Behaviour.decline(null).declineReason()).isEqualTo(ReasonCode.RULE_DECLINED);
        assertThat(Behaviour.decline(ReasonCode.CARD_DECLINED).declineReason()).isEqualTo(ReasonCode.CARD_DECLINED);
        assertThat(Behaviour.decline(ReasonCode.INSUFFICIENT_FUNDS).reasonCode()).isEqualTo(ReasonCode.INSUFFICIENT_FUNDS);
        assertThat(Behaviour.definitiveError().type()).isEqualTo(BehaviourType.DEFINITIVE_ERROR);
        assertThat(Behaviour.transientThenOutcome(0, Outcome.APPROVED, null).transientFailures()).isZero();
        assertThat(Behaviour.transientThenOutcome(3, Outcome.DECLINED, ReasonCode.INSUFFICIENT_FUNDS).declineReason())
                .isEqualTo(ReasonCode.INSUFFICIENT_FUNDS);
        assertThat(Behaviour.transientThenOutcome(Long.MAX_VALUE, Outcome.DECLINED, null).declineReason())
                .isEqualTo(ReasonCode.RULE_DECLINED);
        assertThat(Behaviour.latency(0, null, null).addedLatencyMs()).isZero();
        assertThat(Behaviour.latency(60_000, Outcome.APPROVED, null).finalOutcome()).isEqualTo(Outcome.APPROVED);
        assertThat(Behaviour.latency(10, Outcome.DECLINED, ReasonCode.CARD_DECLINED).reasonCode())
                .isEqualTo(ReasonCode.CARD_DECLINED);
    }

    @Test
    void rejectsFieldsThatDoNotBelongToTheType() {
        assertInvalid(() -> new Behaviour(BehaviourType.APPROVE, 1L, null, null, null), "transientFailures");
        assertInvalid(() -> new Behaviour(BehaviourType.APPROVE, null, Outcome.APPROVED, null, null), "finalOutcome");
        assertInvalid(() -> new Behaviour(BehaviourType.APPROVE, null, null, 5, null), "addedLatencyMs");
        assertInvalid(() -> new Behaviour(BehaviourType.APPROVE, null, null, null, ReasonCode.CARD_DECLINED), "reasonCode");
        assertInvalid(() -> new Behaviour(BehaviourType.DECLINE, 1L, null, null, null), "transientFailures");
        assertInvalid(() -> new Behaviour(BehaviourType.DECLINE, null, Outcome.DECLINED, null, null), "finalOutcome");
        assertInvalid(() -> new Behaviour(BehaviourType.DECLINE, null, null, 1, null), "addedLatencyMs");
        assertInvalid(() -> new Behaviour(BehaviourType.DEFINITIVE_ERROR, null, null, null, ReasonCode.RULE_DECLINED), "reasonCode");
        assertInvalid(() -> new Behaviour(BehaviourType.DEFINITIVE_ERROR, 2L, null, null, null), "transientFailures");
        assertInvalid(() -> new Behaviour(BehaviourType.TRANSIENT_THEN_OUTCOME, 1L, Outcome.APPROVED, 3, null), "addedLatencyMs");
        assertInvalid(() -> new Behaviour(BehaviourType.LATENCY, 1L, null, 3, null), "transientFailures");
    }

    @Test
    void rejectsMissingMandatoryFields() {
        assertInvalid(() -> new Behaviour(null, null, null, null, null), "type");
        assertInvalid(() -> new Behaviour(BehaviourType.TRANSIENT_THEN_OUTCOME, null, Outcome.APPROVED, null, null), "transientFailures");
        assertInvalid(() -> new Behaviour(BehaviourType.TRANSIENT_THEN_OUTCOME, 2L, null, null, null), "finalOutcome");
        assertInvalid(() -> new Behaviour(BehaviourType.LATENCY, null, null, null, null), "addedLatencyMs");
    }

    @Test
    void rejectsOutOfRangeValues() {
        assertInvalid(() -> new Behaviour(BehaviourType.TRANSIENT_THEN_OUTCOME, -1L, Outcome.APPROVED, null, null), "transientFailures");
        assertInvalid(() -> new Behaviour(BehaviourType.LATENCY, null, null, -1, null), "addedLatencyMs");
        assertInvalid(() -> new Behaviour(BehaviourType.LATENCY, null, null, 60_001, null), "addedLatencyMs");
    }

    @Test
    void reasonCodeOnlyWhenTheFinalOutcomeDeclinesAndOnlyConfigurableValues() {
        assertInvalid(() -> Behaviour.transientThenOutcome(1, Outcome.APPROVED, ReasonCode.CARD_DECLINED), "reasonCode");
        assertInvalid(() -> new Behaviour(BehaviourType.LATENCY, null, null, 1, ReasonCode.CARD_DECLINED), "reasonCode");
        assertInvalid(() -> new Behaviour(BehaviourType.LATENCY, null, Outcome.APPROVED, 1, ReasonCode.CARD_DECLINED), "reasonCode");
        assertInvalid(() -> Behaviour.decline(ReasonCode.PERCENTAGE_DECLINED), "reasonCode");
        assertInvalid(() -> Behaviour.decline(ReasonCode.ATTEMPT_CANCELLED), "reasonCode");
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable, String field) {
        assertThatThrownBy(callable).isInstanceOf(InvalidConfigurationException.class).hasMessageContaining(field);
    }
}
