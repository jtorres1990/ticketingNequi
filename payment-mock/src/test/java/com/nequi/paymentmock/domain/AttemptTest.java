package com.nequi.paymentmock.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Authorization transitions of the attempt state machine (ADR-030 rule 1, PM-IV-010, PM-IV-011). */
class AttemptTest {

    static final AttemptPayload PAYLOAD = AttemptPayload.of("order-1", "event-1", "customer-1", List.of("T1"));

    @Test
    void firstInvocationDecidesAndFixesThePayload() {
        AuthorizationStep step = Attempt.empty("a-1").authorize(PAYLOAD, MockConfiguration.INITIAL);
        assertThat(step).isInstanceOf(AuthorizationStep.Decided.class);
        assertThat(((AuthorizationStep.Decided) step).result()).isEqualTo(AuthorizationResult.APPROVED);
        assertThat(step.next().payload()).isEqualTo(PAYLOAD);
        assertThat(step.next().invocations()).isEqualTo(1);
        assertThat(step.next().result()).isEqualTo(AuthorizationResult.APPROVED);
    }

    @Test
    void repetitionReplaysTheStoredResultEvenIfTheConfigurationChanged() {
        Attempt decided = Attempt.empty("a-1").authorize(PAYLOAD, MockConfiguration.INITIAL).next();
        MockConfiguration declineEverything = MockConfiguration.INITIAL.withDefaults(new Defaults(Outcome.DECLINED, 100));
        AuthorizationStep replay = decided.authorize(PAYLOAD, declineEverything);
        assertThat(replay).isEqualTo(new AuthorizationStep.Replayed(replay.next(), AuthorizationResult.APPROVED));
        assertThat(replay.next().invocations()).isEqualTo(2);
        assertThat(replay.next().result()).isEqualTo(AuthorizationResult.APPROVED);
        assertThat(replay.next().cancelledAfterResult()).isFalse();
    }

    @Test
    void anotherPayloadIsAConflictThatChangesNothingButTheCounter() {
        Attempt decided = Attempt.empty("a-1").authorize(PAYLOAD, MockConfiguration.INITIAL).next();
        for (AttemptPayload other : List.of(
                AttemptPayload.of("order-2", "event-1", "customer-1", List.of("T1")),
                AttemptPayload.of("order-1", "event-2", "customer-1", List.of("T1")),
                AttemptPayload.of("order-1", "event-1", "customer-2", List.of("T1")),
                AttemptPayload.of("order-1", "event-1", "customer-1", List.of("T1", "T2")))) {
            AuthorizationStep conflict = decided.authorize(other, MockConfiguration.INITIAL);
            assertThat(conflict).isInstanceOf(AuthorizationStep.PayloadConflict.class);
            assertThat(conflict.next().payload()).isEqualTo(PAYLOAD);
            assertThat(conflict.next().result()).isEqualTo(AuthorizationResult.APPROVED);
            assertThat(conflict.next().invocations()).isEqualTo(2);
        }
        // The same Tickets in another order and with repetitions are the same payload (set comparison).
        assertThat(decided.authorize(AttemptPayload.of("order-1", "event-1", "customer-1", List.of("T1", "T1")),
                MockConfiguration.INITIAL)).isInstanceOf(AuthorizationStep.Replayed.class);
    }

    @Test
    void inspectionHelpersReflectTheState() {
        Attempt empty = Attempt.empty("a-1");
        assertThat(empty.hasAuthorizations()).isFalse();
        assertThat(empty.toString()).doesNotContain("customer");
        Attempt decided = empty.authorize(PAYLOAD, MockConfiguration.INITIAL).next();
        assertThat(decided.hasAuthorizations()).isTrue();
        assertThat(decided.toString()).doesNotContain("customer-1");
    }
}
