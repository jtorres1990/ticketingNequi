package com.nequi.paymentmock.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.nequi.paymentmock.domain.AttemptPayload;
import com.nequi.paymentmock.domain.AuthorizationResult;
import com.nequi.paymentmock.domain.Behaviour;
import com.nequi.paymentmock.domain.CancellationStatus;
import com.nequi.paymentmock.domain.MatchField;
import com.nequi.paymentmock.domain.Outcome;
import com.nequi.paymentmock.domain.ReasonCode;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.scheduler.Schedulers;

/**
 * G7 race tests with real threads and the real parallel scheduler (BlockHound installed): each scenario is repeated
 * hundreds of times and asserts invariants, never timings (ADR-030 rules 1 to 3, FG-003, AC-035, PM-IV-014).
 */
class RaceConditionTest {

    static final AttemptPayload PAYLOAD = AttemptPayload.of("order-1", "event-1", "customer-1", List.of("T1"));
    static final int ITERATIONS = 400;

    private final MockState state = new MockState();
    private final ControlService control = new ControlService(state);
    private final PaymentService payments = new PaymentService(state, Schedulers.parallel(), Clock.systemUTC());
    private final ExecutorService executor = Executors.newFixedThreadPool(8);

    @AfterEach
    void stop() {
        executor.shutdownNow();
    }

    AuthorizationReply.Authorized authorize(String id) {
        return (AuthorizationReply.Authorized) payments.authorize(id, PAYLOAD).block(Duration.ofSeconds(10));
    }

    CancellationReply cancel(String id) {
        return payments.cancel(id).block(Duration.ofSeconds(10));
    }

    /** Runs the tasks simultaneously behind a barrier, in the given order of submission. */
    <T> List<T> simultaneously(List<Callable<T>> tasks) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(tasks.size());
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> task : tasks) {
            futures.add(executor.submit(() -> {
                barrier.await();
                return task.call();
            }));
        }
        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            results.add(future.get());
        }
        return results;
    }

    Map<String, Integer> authorizeAgainstCancel(Set<String> allowedPairs) throws Exception {
        Map<String, Integer> observed = new HashMap<>();
        for (int i = 0; i < ITERATIONS; i++) {
            String id = "ac-" + i;
            List<Callable<Object>> tasks = new ArrayList<>();
            tasks.add(() -> authorize(id));
            tasks.add(() -> authorize(id));
            tasks.add(() -> cancel(id));
            tasks.add(() -> cancel(id));
            if (i % 2 == 1) {
                java.util.Collections.reverse(tasks); // both submission orders
            }
            List<Object> replies = simultaneously(tasks);
            List<AuthorizationReply.Authorized> auths = replies.stream()
                    .filter(AuthorizationReply.Authorized.class::isInstance)
                    .map(AuthorizationReply.Authorized.class::cast).toList();
            List<CancellationReply> cancels = replies.stream()
                    .filter(CancellationReply.class::isInstance).map(CancellationReply.class::cast).toList();

            assertThat(auths).filteredOn(reply -> !reply.replayed()).hasSize(1);
            assertThat(auths).extracting(AuthorizationReply.Authorized::result).containsOnly(auths.getFirst().result());
            assertThat(cancels).filteredOn(reply -> !reply.replayed()).hasSize(1);
            assertThat(cancels).extracting(CancellationReply::status).containsOnly(cancels.getFirst().status());

            AuthorizationView authorization = control.authorization(id).orElseThrow();
            CancellationView cancellation = control.cancellation(id).orElseThrow();
            assertThat(authorization.invocations()).isEqualTo(2);
            assertThat(cancellation.received()).isEqualTo(2);
            assertThat(authorization.result()).isEqualTo(auths.getFirst().result());
            assertThat(cancellation.status()).isEqualTo(cancels.getFirst().status());
            String pair = authorization.result().status() + "/" + authorization.result().reasonCode() + "/"
                    + cancellation.status();
            assertThat(allowedPairs).as("observed pair").contains(pair);
            boolean cancelledAfter = cancellation.status() != CancellationStatus.REGISTERED_BEFORE_CHARGE;
            assertThat(authorization.cancelled()).isEqualTo(cancelledAfter);
            observed.merge(pair, 1, Integer::sum);
        }
        System.out.println("[race] authorize/cancel observed pairs: " + observed);
        return observed;
    }

    @Test
    void authorizeAndCancelRaceOnlyEverProducesConsistentPairs() throws Exception {
        Map<String, Integer> observed = authorizeAgainstCancel(Set.of(
                "APPROVED/null/REVERSED",
                "DECLINED/ATTEMPT_CANCELLED/REGISTERED_BEFORE_CHARGE"));
        assertThat(observed.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(ITERATIONS);
    }

    @Test
    void authorizeAndCancelRaceWithADeclineRule() throws Exception {
        control.createRule(MatchField.ORDER_ID, "order-1", Behaviour.decline(ReasonCode.CARD_DECLINED));
        authorizeAgainstCancel(Set.of(
                "DECLINED/CARD_DECLINED/VOIDED",
                "DECLINED/ATTEMPT_CANCELLED/REGISTERED_BEFORE_CHARGE"));
    }

    @Test
    void cancellationRacesTheCommitOfALatencyDecision() throws Exception {
        // The commit of the decision runs on the thread that advances virtual time, racing the cancellation exactly
        // at the decision boundary (PM-IV-013: a cancellation registered before the commit wins).
        reactor.test.scheduler.VirtualTimeScheduler time = reactor.test.scheduler.VirtualTimeScheduler.create();
        PaymentService virtual = new PaymentService(state, time, Clock.systemUTC());
        control.createRule(MatchField.ORDER_ID, "order-1", Behaviour.latency(1, Outcome.APPROVED, null));
        Map<String, Integer> observed = new HashMap<>();
        for (int i = 0; i < ITERATIONS; i++) {
            String id = "lc-" + i;
            java.util.concurrent.atomic.AtomicReference<AuthorizationReply> reply =
                    new java.util.concurrent.atomic.AtomicReference<>();
            virtual.authorize(id, PAYLOAD).subscribe(reply::set);
            List<Callable<Object>> tasks = new ArrayList<>();
            tasks.add(() -> {
                time.advanceTimeBy(Duration.ofMillis(1));
                return "commit";
            });
            tasks.add(() -> cancel(id));
            if (i % 2 == 1) {
                java.util.Collections.reverse(tasks);
            }
            List<Object> results = simultaneously(tasks);
            AuthorizationReply.Authorized auth = (AuthorizationReply.Authorized) reply.get();
            CancellationReply cancellation = (CancellationReply) results.stream()
                    .filter(CancellationReply.class::isInstance).findFirst().orElseThrow();
            String pair = auth.result().reasonCode() + "/" + cancellation.status();
            assertThat(pair).isIn("null/REVERSED", "ATTEMPT_CANCELLED/REGISTERED_BEFORE_CHARGE");
            assertThat(control.authorization(id).orElseThrow().result()).isEqualTo(auth.result());
            // Never an approved charge with a cancellation registered before it (BR-034).
            if (cancellation.status() == CancellationStatus.REGISTERED_BEFORE_CHARGE) {
                assertThat(auth.result()).isEqualTo(AuthorizationResult.ATTEMPT_CANCELLED);
            }
            observed.merge(pair, 1, Integer::sum);
        }
        System.out.println("[race] latency-commit/cancel observed pairs: " + observed);
        assertThat(observed.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(ITERATIONS);
        time.dispose();
    }

    @Test
    void concurrentCancellationsFixOneStatusAndCountEveryCall() throws Exception {
        for (int i = 0; i < ITERATIONS; i++) {
            String id = "cc-" + i;
            if (i % 3 == 0) {
                authorize(id);
            }
            List<Callable<CancellationReply>> tasks = new ArrayList<>();
            for (int t = 0; t < 6; t++) {
                tasks.add(() -> cancel(id));
            }
            List<CancellationReply> replies = simultaneously(tasks);
            assertThat(replies).filteredOn(reply -> !reply.replayed()).hasSize(1);
            assertThat(replies).extracting(CancellationReply::status).containsOnly(i % 3 == 0
                    ? CancellationStatus.REVERSED : CancellationStatus.REGISTERED_BEFORE_CHARGE);
            assertThat(control.cancellation(id).orElseThrow().received()).isEqualTo(6);
        }
    }

    @Test
    void concurrentRepetitionsJoinAPendingDecision() throws Exception {
        control.createRule(MatchField.ORDER_ID, "order-1", Behaviour.latency(5, Outcome.APPROVED, null));
        for (int i = 0; i < ITERATIONS / 4; i++) {
            String id = "jp-" + i;
            List<Callable<AuthorizationReply.Authorized>> tasks = new ArrayList<>();
            for (int t = 0; t < 6; t++) {
                tasks.add(() -> authorize(id));
            }
            List<AuthorizationReply.Authorized> replies = simultaneously(tasks);
            assertThat(replies).filteredOn(reply -> !reply.replayed()).hasSize(1);
            assertThat(replies).extracting(AuthorizationReply.Authorized::result).containsOnly(AuthorizationResult.APPROVED);
            assertThat(control.authorization(id).orElseThrow().invocations()).isEqualTo(6);
        }
    }

    @Test
    void resetRacingAuthorizationsAndCancellationsNeverMixesGenerations() throws Exception {
        for (int i = 0; i < ITERATIONS; i++) {
            String id = "rs-" + i;
            simultaneously(List.<Callable<Object>>of(() -> authorize(id), () -> cancel(id), () -> {
                control.reset();
                return "reset";
            }));
            // Whatever ran after the reset is a consistent state of the new generation only.
            control.authorization(id).ifPresent(view -> {
                assertThat(view.invocations()).isEqualTo(1);
                assertThat(view.result()).isNotNull();
            });
            control.cancellation(id).ifPresent(view -> {
                assertThat(view.received()).isEqualTo(1);
                AuthorizationView authorization = control.authorization(id).orElse(null);
                if (view.status() == CancellationStatus.REVERSED) {
                    assertThat(authorization).isNotNull();
                    assertThat(authorization.result()).isEqualTo(AuthorizationResult.APPROVED);
                }
                if (authorization != null && authorization.result().equals(AuthorizationResult.ATTEMPT_CANCELLED)) {
                    assertThat(view.status()).isEqualTo(CancellationStatus.REGISTERED_BEFORE_CHARGE);
                }
            });
            control.reset();
        }
    }

    @Test
    void resetDuringPendingDecisionsLeavesNothingVisible() throws Exception {
        control.createRule(MatchField.ORDER_ID, "order-1", Behaviour.latency(20, Outcome.APPROVED, null));
        List<Future<AuthorizationReply.Authorized>> pending = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            String id = "pd-" + i;
            pending.add(executor.submit(() -> authorize(id)));
        }
        control.reset();
        for (Future<AuthorizationReply.Authorized> future : pending) {
            assertThat(future.get().result()).isEqualTo(AuthorizationResult.APPROVED);
        }
        for (int i = 0; i < 50; i++) {
            // Either the authorization started before the reset (invisible) or after it (no latency rule any more).
            control.authorization("pd-" + i).ifPresent(view -> assertThat(view.invocations()).isEqualTo(1));
        }
        assertThat(control.listRules()).isEmpty();
    }
}
