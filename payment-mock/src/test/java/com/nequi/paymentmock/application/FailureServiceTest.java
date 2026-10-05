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
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import reactor.core.scheduler.Schedulers;

/** Simulated failures at service level, including concurrency and reset (PM-IV-009, PM-IV-014, G7). */
class FailureServiceTest {

    static final AttemptPayload PAYLOAD = AttemptPayload.of("order-1", "event-1", "customer-1", List.of("T1"));

    private final MockState state = new MockState();
    private final ControlService control = new ControlService(state);
    private final PaymentService payments = new PaymentService(state, Schedulers.parallel(), Clock.systemUTC());

    AuthorizationReply authorize(String id) {
        return payments.authorize(id, PAYLOAD).block(Duration.ofSeconds(5));
    }

    @Test
    void definitiveErrorIsRepeatedAndCounted() {
        control.createRule(MatchField.EVENT_ID, "event-1", Behaviour.definitiveError());
        for (int i = 0; i < 3; i++) {
            assertThat(authorize("a-1")).isEqualTo(new AuthorizationReply.DefinitiveError());
        }
        assertThat(control.authorization("a-1")).contains(new AuthorizationView("a-1", 3, null, false));
        assertThat(payments.cancel("a-1").block().status()).isEqualTo(CancellationStatus.REGISTERED_BEFORE_CHARGE);
        assertThat(authorize("a-1")).isEqualTo(
                new AuthorizationReply.Authorized("a-1", AuthorizationResult.ATTEMPT_CANCELLED, false, false));
    }

    @Test
    void transientFailuresThenFinalResultAndResetClearsProgress() {
        control.createRule(MatchField.ORDER_ID, "order-1",
                Behaviour.transientThenOutcome(2, Outcome.DECLINED, ReasonCode.INSUFFICIENT_FUNDS));
        assertThat(authorize("a-1")).isEqualTo(new AuthorizationReply.TransientFailure());
        control.reset();
        control.createRule(MatchField.ORDER_ID, "order-1",
                Behaviour.transientThenOutcome(2, Outcome.DECLINED, ReasonCode.INSUFFICIENT_FUNDS));
        assertThat(authorize("a-1")).isEqualTo(new AuthorizationReply.TransientFailure());
        assertThat(authorize("a-1")).isEqualTo(new AuthorizationReply.TransientFailure());
        AuthorizationResult declined = AuthorizationResult.declined(ReasonCode.INSUFFICIENT_FUNDS);
        assertThat(authorize("a-1")).isEqualTo(new AuthorizationReply.Authorized("a-1", declined, false, false));
        assertThat(authorize("a-1")).isEqualTo(new AuthorizationReply.Authorized("a-1", declined, true, false));
        assertThat(control.authorization("a-1")).contains(new AuthorizationView("a-1", 4, declined, false));
    }

    @Test
    void concurrentInvocationsConsumeExactlyTheConfiguredFailures() throws Exception {
        control.createRule(MatchField.ORDER_ID, "order-1", Behaviour.transientThenOutcome(3, Outcome.APPROVED, null));
        int threads = 8;
        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            for (int iteration = 0; iteration < 300; iteration++) {
                String id = "t-" + iteration;
                CyclicBarrier barrier = new CyclicBarrier(threads);
                List<Future<AuthorizationReply>> futures = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    futures.add(executor.submit(() -> {
                        barrier.await();
                        return authorize(id);
                    }));
                }
                List<AuthorizationReply> replies = new ArrayList<>();
                for (Future<AuthorizationReply> future : futures) {
                    replies.add(future.get());
                }
                assertThat(replies).filteredOn(AuthorizationReply.TransientFailure.class::isInstance).hasSize(3);
                assertThat(replies).filteredOn(reply -> reply instanceof AuthorizationReply.Authorized authorized
                        && !authorized.replayed()).hasSize(1);
                assertThat(replies).filteredOn(reply -> reply instanceof AuthorizationReply.Authorized authorized
                        && authorized.replayed()).hasSize(4);
                assertThat(control.authorization(id).orElseThrow().invocations()).isEqualTo(threads);
            }
        }
    }
}
