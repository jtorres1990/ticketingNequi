package com.nequi.paymentmock.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.nequi.paymentmock.domain.AttemptPayload;
import com.nequi.paymentmock.domain.AuthorizationResult;
import com.nequi.paymentmock.domain.Behaviour;
import com.nequi.paymentmock.domain.CancellationStatus;
import com.nequi.paymentmock.domain.Defaults;
import com.nequi.paymentmock.domain.MatchField;
import com.nequi.paymentmock.domain.Outcome;
import com.nequi.paymentmock.domain.ReasonCode;
import com.nequi.paymentmock.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;
import reactor.test.scheduler.VirtualTimeScheduler;

/**
 * PM-SPK-004 and API-102/API-109 at service level with virtual time and a controllable clock: latency decided at the
 * end of the wait, cancellation winning during the wait, joining repetitions, caller disconnection, reset during
 * the wait, early cancellation and cancellation idempotency (FG-003, AC-035, PM-IV-010, PM-IV-011, PM-IV-013,
 * PM-IV-014).
 */
class LatencyAndCancellationServiceTest {

    static final AttemptPayload PAYLOAD = AttemptPayload.of("order-1", "event-1", "customer-1", List.of("T1"));
    static final Instant T0 = Instant.parse("2026-10-04T15:00:00.123Z");

    private final VirtualTimeScheduler time = VirtualTimeScheduler.create();
    private final MutableClock clock = new MutableClock(T0);
    private final MockState state = new MockState();
    private final ControlService control = new ControlService(state);
    private final PaymentService payments = new PaymentService(state, time, clock);

    @AfterEach
    void disposeScheduler() {
        time.dispose();
    }

    void latency(int ms, Outcome finalOutcome) {
        control.createRule(MatchField.ORDER_ID, "order-1", Behaviour.latency(ms, finalOutcome, null));
    }

    AtomicReference<AuthorizationReply> authorizeAsync(String id) {
        AtomicReference<AuthorizationReply> reply = new AtomicReference<>();
        payments.authorize(id, PAYLOAD).subscribe(reply::set);
        return reply;
    }

    CancellationReply cancel(String id) {
        AtomicReference<CancellationReply> reply = new AtomicReference<>();
        payments.cancel(id).subscribe(reply::set);
        return reply.get();
    }

    @Test
    void latencyDecidesOnlyWhenTheWaitEnds() {
        latency(500, Outcome.APPROVED);
        AtomicReference<AuthorizationReply> reply = authorizeAsync("a-1");
        time.advanceTimeBy(Duration.ofMillis(499));
        assertThat(reply.get()).isNull();
        assertThat(control.authorization("a-1")).contains(new AuthorizationView("a-1", 1, null, false));
        time.advanceTimeBy(Duration.ofMillis(1));
        assertThat(reply.get()).isEqualTo(new AuthorizationReply.Authorized("a-1", AuthorizationResult.APPROVED, false, false));
        assertThat(control.authorization("a-1")).contains(new AuthorizationView("a-1", 1, AuthorizationResult.APPROVED, false));
        // Later repetitions answer immediately, without new latency.
        assertThat(authorizeAsync("a-1").get())
                .isEqualTo(new AuthorizationReply.Authorized("a-1", AuthorizationResult.APPROVED, true, false));
    }

    @Test
    void latencyWithoutFinalOutcomeUsesThePercentageAndDefaultSnapshotTakenOnArrival() {
        control.createRule(MatchField.ORDER_ID, "order-1", Behaviour.latency(100, null, null));
        control.setDefaults(new Defaults(Outcome.APPROVED, 100));
        AtomicReference<AuthorizationReply> reply = authorizeAsync("a-1");
        control.setDefaults(Defaults.INITIAL);
        time.advanceTimeBy(Duration.ofMillis(100));
        assertThat(((AuthorizationReply.Authorized) reply.get()).result())
                .isEqualTo(AuthorizationResult.declined(ReasonCode.PERCENTAGE_DECLINED));
    }

    @Test
    void zeroLatencyCommitsThroughTheSameDecisionPath() {
        latency(0, Outcome.DECLINED);
        AtomicReference<AuthorizationReply> reply = authorizeAsync("a-1");
        time.advanceTime();
        assertThat(control.authorization("a-1").orElseThrow().result())
                .isEqualTo(AuthorizationResult.declined(ReasonCode.RULE_DECLINED));
        assertThat(((AuthorizationReply.Authorized) reply.get()).result())
                .isEqualTo(AuthorizationResult.declined(ReasonCode.RULE_DECLINED));
    }

    @Test
    void cancellationDuringTheWaitWinsAndTheAttemptIsDeclinedAsCancelled() {
        latency(1_000, Outcome.APPROVED);
        AtomicReference<AuthorizationReply> reply = authorizeAsync("a-1");
        time.advanceTimeBy(Duration.ofMillis(400));
        assertThat(cancel("a-1")).isEqualTo(new CancellationReply("a-1", CancellationStatus.REGISTERED_BEFORE_CHARGE, false));
        time.advanceTimeBy(Duration.ofMillis(600));
        assertThat(reply.get()).isEqualTo(
                new AuthorizationReply.Authorized("a-1", AuthorizationResult.ATTEMPT_CANCELLED, false, false));
        assertThat(control.authorization("a-1")).contains(
                new AuthorizationView("a-1", 1, AuthorizationResult.ATTEMPT_CANCELLED, false));
        assertThat(control.cancellation("a-1")).contains(
                new CancellationView("a-1", 1, CancellationStatus.REGISTERED_BEFORE_CHARGE, T0));
    }

    @Test
    void repetitionsDuringTheWaitJoinTheSameDecision() {
        latency(300, Outcome.APPROVED);
        AtomicReference<AuthorizationReply> first = authorizeAsync("a-1");
        time.advanceTimeBy(Duration.ofMillis(100));
        AtomicReference<AuthorizationReply> second = authorizeAsync("a-1");
        // The rule changes meanwhile: the join does not evaluate rules again.
        control.deleteAllRules();
        control.setDefaults(new Defaults(Outcome.DECLINED, 0));
        time.advanceTimeBy(Duration.ofMillis(100));
        AtomicReference<AuthorizationReply> third = authorizeAsync("a-1");
        assertThat(first.get()).isNull();
        assertThat(second.get()).isNull();
        time.advanceTimeBy(Duration.ofMillis(100));
        assertThat(first.get()).isEqualTo(new AuthorizationReply.Authorized("a-1", AuthorizationResult.APPROVED, false, false));
        assertThat(second.get()).isEqualTo(new AuthorizationReply.Authorized("a-1", AuthorizationResult.APPROVED, true, false));
        assertThat(third.get()).isEqualTo(new AuthorizationReply.Authorized("a-1", AuthorizationResult.APPROVED, true, false));
        assertThat(control.authorization("a-1").orElseThrow().invocations()).isEqualTo(3);
    }

    @Test
    void theDecisionIsCommittedEvenIfTheCallerDisconnects() {
        latency(3_500, Outcome.APPROVED);
        Disposable caller = payments.authorize("a-1", PAYLOAD).subscribe();
        time.advanceTimeBy(Duration.ofMillis(3_000));
        caller.dispose(); // caller timeout of ticketing (3 s)
        time.advanceTimeBy(Duration.ofMillis(500));
        assertThat(control.authorization("a-1")).contains(new AuthorizationView("a-1", 1, AuthorizationResult.APPROVED, false));
        // Its cancellation (e.g. by the reversal process of ticketing) now reverses the charge in transit.
        assertThat(cancel("a-1").status()).isEqualTo(CancellationStatus.REVERSED);
    }

    @Test
    void resetDuringTheWaitKeepsTheDecisionInTheOldGeneration() {
        latency(200, Outcome.APPROVED);
        AtomicReference<AuthorizationReply> reply = authorizeAsync("a-1");
        control.reset();
        time.advanceTimeBy(Duration.ofMillis(200));
        assertThat(reply.get()).isEqualTo(new AuthorizationReply.Authorized("a-1", AuthorizationResult.APPROVED, false, false));
        assertThat(control.authorization("a-1")).isEmpty();
        assertThat(control.listRules()).isEmpty();
        // The attempt starts again in the new generation (without the latency rule).
        assertThat(authorizeAsync("a-1").get())
                .isEqualTo(new AuthorizationReply.Authorized("a-1", AuthorizationResult.APPROVED, false, false));
    }

    @Test
    void cancellationStatusesAndIdempotency() {
        assertThat(authorizeAsync("approved").get()).isNotNull();
        control.setDefaults(new Defaults(Outcome.DECLINED, 0));
        assertThat(authorizeAsync("declined").get()).isNotNull();

        assertThat(cancel("approved")).isEqualTo(new CancellationReply("approved", CancellationStatus.REVERSED, false));
        clock.advance(Duration.ofSeconds(7));
        assertThat(cancel("approved")).isEqualTo(new CancellationReply("approved", CancellationStatus.REVERSED, true));
        assertThat(cancel("declined")).isEqualTo(new CancellationReply("declined", CancellationStatus.VOIDED, false));
        assertThat(cancel("missing")).isEqualTo(
                new CancellationReply("missing", CancellationStatus.REGISTERED_BEFORE_CHARGE, false));

        assertThat(control.cancellation("approved")).contains(
                new CancellationView("approved", 2, CancellationStatus.REVERSED, T0));
        assertThat(control.cancellation("declined")).contains(
                new CancellationView("declined", 1, CancellationStatus.VOIDED, T0.plusSeconds(7)));
        assertThat(control.cancellation("never")).isEmpty();
        // A cancellation alone is not an authorization (API-108 stays 404).
        assertThat(control.authorization("missing")).isEmpty();

        // Repetitions of the authorization report the later cancellation.
        assertThat(authorizeAsync("approved").get())
                .isEqualTo(new AuthorizationReply.Authorized("approved", AuthorizationResult.APPROVED, true, true));
        assertThat(control.authorization("approved").orElseThrow().cancelled()).isTrue();
        assertThat(authorizeAsync("declined").get()).isEqualTo(new AuthorizationReply.Authorized("declined",
                AuthorizationResult.declined(ReasonCode.RULE_DECLINED), true, true));
    }

    @Test
    void earlyCancellationRejectsEveryLaterAuthorizationWhateverTheConfiguration() {
        assertThat(cancel("a-1").status()).isEqualTo(CancellationStatus.REGISTERED_BEFORE_CHARGE);
        control.createRule(MatchField.ORDER_ID, "order-1", Behaviour.approve());
        control.setDefaults(new Defaults(Outcome.APPROVED, 0));
        assertThat(authorizeAsync("a-1").get())
                .isEqualTo(new AuthorizationReply.Authorized("a-1", AuthorizationResult.ATTEMPT_CANCELLED, false, false));
        for (int i = 0; i < 3; i++) {
            assertThat(authorizeAsync("a-1").get())
                    .isEqualTo(new AuthorizationReply.Authorized("a-1", AuthorizationResult.ATTEMPT_CANCELLED, true, false));
        }
        assertThat(control.authorization("a-1")).contains(
                new AuthorizationView("a-1", 4, AuthorizationResult.ATTEMPT_CANCELLED, false));
        assertThat(cancel("a-1")).isEqualTo(new CancellationReply("a-1", CancellationStatus.REGISTERED_BEFORE_CHARGE, true));
        // Early cancellation also wins over a LATENCY rule: no wait is started.
        control.createRule(MatchField.ORDER_ID, "order-1", Behaviour.latency(10_000, Outcome.APPROVED, null));
        cancel("b-1");
        assertThat(authorizeAsync("b-1").get())
                .isEqualTo(new AuthorizationReply.Authorized("b-1", AuthorizationResult.ATTEMPT_CANCELLED, false, false));
    }

    @Test
    void resetForgetsCancellations() {
        cancel("a-1");
        control.reset();
        assertThat(control.cancellation("a-1")).isEmpty();
        assertThat(authorizeAsync("a-1").get())
                .isEqualTo(new AuthorizationReply.Authorized("a-1", AuthorizationResult.APPROVED, false, false));
    }

    @Test
    void realParallelSchedulerLatencyCommitsWithoutBlockingReactorThreads() {
        PaymentService real = new PaymentService(state, Schedulers.parallel(), clock);
        latency(30, Outcome.APPROVED);
        // The commit runs on a parallel thread with BlockHound installed (G4); block() here runs on the test thread.
        AuthorizationReply reply = real.authorize("r-1", PAYLOAD).block(Duration.ofSeconds(5));
        assertThat(reply).isEqualTo(new AuthorizationReply.Authorized("r-1", AuthorizationResult.APPROVED, false, false));
    }
}
