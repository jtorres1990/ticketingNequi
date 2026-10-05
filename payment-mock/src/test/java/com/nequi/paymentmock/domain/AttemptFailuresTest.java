package com.nequi.paymentmock.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** DEFINITIVE_ERROR and TRANSIENT_THEN_OUTCOME on the state machine (AC-021, ALT-004, ERR-008, PM-IV-009). */
class AttemptFailuresTest {

    static final AttemptPayload PAYLOAD = AttemptPayload.of("order-1", "event-1", "customer-1", List.of("T1"));
    static final Instant T0 = Instant.parse("2026-10-04T10:00:00Z");

    static MockConfiguration with(OutcomeRule... rules) {
        RuleSet set = RuleSet.EMPTY;
        for (OutcomeRule rule : rules) {
            set = set.add(rule);
        }
        return MockConfiguration.INITIAL.withRules(set);
    }

    static OutcomeRule transientRule(long sequence, long failures, Outcome finalOutcome) {
        return OutcomeRule.create(sequence, MatchField.ORDER_ID, "order-1",
                Behaviour.transientThenOutcome(failures, finalOutcome, null));
    }

    /** Runs {@code invocations} authorizations and returns the step types. */
    static List<String> run(Attempt[] attempt, MockConfiguration configuration, int invocations) {
        List<String> steps = new ArrayList<>();
        for (int i = 0; i < invocations; i++) {
            AuthorizationStep step = attempt[0].authorize(PAYLOAD, configuration);
            attempt[0] = step.next();
            steps.add(step.getClass().getSimpleName());
        }
        return steps;
    }

    @Test
    void definitiveErrorAnswersEveryInvocationWithoutStoringAResult() {
        Attempt[] attempt = {Attempt.empty("a-1")};
        MockConfiguration configuration = with(OutcomeRule.create(1, MatchField.ORDER_ID, "order-1",
                Behaviour.definitiveError()));
        assertThat(run(attempt, configuration, 4)).containsOnly("DefinitiveError").hasSize(4);
        assertThat(attempt[0].result()).isNull();
        assertThat(attempt[0].invocations()).isEqualTo(4);
        assertThat(attempt[0].payload()).isEqualTo(PAYLOAD);
        // After a definitive error a cancellation registers before charge and later authorizations are cancelled.
        CancellationStep cancellation = attempt[0].cancel(T0);
        assertThat(cancellation.status()).isEqualTo(CancellationStatus.REGISTERED_BEFORE_CHARGE);
        attempt[0] = cancellation.next();
        assertThat(attempt[0].authorize(PAYLOAD, configuration)).isInstanceOf(AuthorizationStep.Decided.class)
                .extracting(step -> ((AuthorizationStep.Decided) step).result())
                .isEqualTo(AuthorizationResult.ATTEMPT_CANCELLED);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 3})
    void transientFailuresAreConsumedExactlyThenTheFinalResultIsStored(int failures) {
        Attempt[] attempt = {Attempt.empty("a-1")};
        MockConfiguration configuration = with(transientRule(1, failures, Outcome.DECLINED));
        List<String> steps = run(attempt, configuration, failures + 3);
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < failures; i++) {
            expected.add("TransientFailure");
        }
        expected.add("Decided");
        expected.add("Replayed");
        expected.add("Replayed");
        assertThat(steps).containsExactlyElementsOf(expected);
        assertThat(attempt[0].result()).isEqualTo(AuthorizationResult.declined(ReasonCode.RULE_DECLINED));
        assertThat(attempt[0].invocations()).isEqualTo(failures + 3);
        assertThat(attempt[0].transientProgress()).isEqualTo(failures == 0 ? java.util.Map.of() : java.util.Map.of("rule-1", (long) failures));
    }

    @Test
    void progressIsKeptPerAttemptAndRule() {
        Attempt[] attempt = {Attempt.empty("a-1")};
        OutcomeRule first = transientRule(1, 3, Outcome.APPROVED);
        assertThat(run(attempt, with(first), 2)).containsOnly("TransientFailure");
        // A newer rule replaces it: its progress starts from zero.
        OutcomeRule second = transientRule(2, 2, Outcome.APPROVED);
        assertThat(run(attempt, with(first, second), 2)).containsOnly("TransientFailure");
        // Back to the first rule (second deleted): its own progress (2 of 3) continues.
        assertThat(run(attempt, with(first), 2)).containsExactly("TransientFailure", "Decided");
        assertThat(attempt[0].invocations()).isEqualTo(6);
        // Another attempt has its own progress.
        Attempt[] other = {Attempt.empty("a-2")};
        assertThat(run(other, with(first), 1)).containsExactly("TransientFailure");
    }

    @Test
    void cancellationInterruptsTheRemainingFailures() {
        Attempt[] attempt = {Attempt.empty("a-1")};
        MockConfiguration configuration = with(transientRule(1, 5, Outcome.APPROVED));
        run(attempt, configuration, 2);
        CancellationStep cancellation = attempt[0].cancel(T0);
        assertThat(cancellation.status()).isEqualTo(CancellationStatus.REGISTERED_BEFORE_CHARGE);
        attempt[0] = cancellation.next();
        assertThat(run(attempt, configuration, 2)).containsExactly("Decided", "Replayed");
        assertThat(attempt[0].result()).isEqualTo(AuthorizationResult.ATTEMPT_CANCELLED);
    }

    @Test
    void finalApprovedResultUsesNoReasonAndRepetitionsDoNotConsumeFailures() {
        Attempt[] attempt = {Attempt.empty("a-1")};
        MockConfiguration configuration = with(transientRule(1, 1, Outcome.APPROVED));
        assertThat(run(attempt, configuration, 5)).containsExactly("TransientFailure", "Decided", "Replayed",
                "Replayed", "Replayed");
        assertThat(attempt[0].result()).isEqualTo(AuthorizationResult.APPROVED);
        assertThat(attempt[0].transientProgress()).containsEntry("rule-1", 1L);
    }
}
