package com.nequi.paymentmock.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.nequi.paymentmock.domain.AttemptPayload;
import com.nequi.paymentmock.domain.AuthorizationResult;
import com.nequi.paymentmock.domain.Behaviour;
import com.nequi.paymentmock.domain.Defaults;
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

/** API-101 and API-108 at service level: determinism, idempotency, inspection, reset and concurrency (G7). */
class PaymentServiceTest {

    static final AttemptPayload PAYLOAD = AttemptPayload.of("order-1", "event-1", "customer-1", List.of("T1"));

    private final MockState state = new MockState();
    private final ControlService control = new ControlService(state);
    private final PaymentService payments = new PaymentService(state, Schedulers.parallel(), Clock.systemUTC());

    AuthorizationReply authorize(String id, AttemptPayload payload) {
        return payments.authorize(id, payload).block(Duration.ofSeconds(5));
    }

    @Test
    void approvesByDefaultAndReplaysWithTheSameResult() {
        assertThat(authorize("a-1", PAYLOAD))
                .isEqualTo(new AuthorizationReply.Authorized("a-1", AuthorizationResult.APPROVED, false, false));
        assertThat(authorize("a-1", PAYLOAD))
                .isEqualTo(new AuthorizationReply.Authorized("a-1", AuthorizationResult.APPROVED, true, false));
        assertThat(control.authorization("a-1")).contains(
                new AuthorizationView("a-1", 2, AuthorizationResult.APPROVED, false));
    }

    @Test
    void declinesByRuleAndKeepsTheStoredResultWhenRulesChange() {
        control.createRule(MatchField.CUSTOMER_REF, "customer-1", Behaviour.decline(ReasonCode.INSUFFICIENT_FUNDS));
        AuthorizationResult declined = AuthorizationResult.declined(ReasonCode.INSUFFICIENT_FUNDS);
        assertThat(authorize("a-1", PAYLOAD)).isEqualTo(new AuthorizationReply.Authorized("a-1", declined, false, false));
        control.deleteAllRules();
        control.setDefaults(new Defaults(Outcome.APPROVED, 0));
        assertThat(authorize("a-1", PAYLOAD)).isEqualTo(new AuthorizationReply.Authorized("a-1", declined, true, false));
        // A new attempt of the same Order follows the new configuration.
        assertThat(authorize("a-2", PAYLOAD))
                .isEqualTo(new AuthorizationReply.Authorized("a-2", AuthorizationResult.APPROVED, false, false));
    }

    @Test
    void payloadConflictIsCountedAndChangesNothing() {
        authorize("a-1", PAYLOAD);
        AttemptPayload other = AttemptPayload.of("order-2", "event-1", "customer-1", List.of("T1"));
        assertThat(authorize("a-1", other)).isEqualTo(new AuthorizationReply.PayloadConflict());
        assertThat(control.authorization("a-1")).contains(
                new AuthorizationView("a-1", 2, AuthorizationResult.APPROVED, false));
    }

    @Test
    void inspectionIsReadOnlyAndEmptyWithoutAuthorizations() {
        assertThat(control.authorization("missing")).isEmpty();
        assertThat(control.authorization("missing")).isEmpty();
        authorize("a-1", PAYLOAD);
        for (int i = 0; i < 3; i++) {
            assertThat(control.authorization("a-1")).contains(
                    new AuthorizationView("a-1", 1, AuthorizationResult.APPROVED, false));
        }
    }

    @Test
    void resetForgetsAuthorizations() {
        control.setDefaults(new Defaults(Outcome.DECLINED, 0));
        authorize("a-1", PAYLOAD);
        control.reset();
        assertThat(control.authorization("a-1")).isEmpty();
        assertThat(authorize("a-1", PAYLOAD))
                .isEqualTo(new AuthorizationReply.Authorized("a-1", AuthorizationResult.APPROVED, false, false));
    }

    @Test
    void concurrentAuthorizationsOfTheSameAttemptDecideOnce() throws Exception {
        int threads = 8;
        int iterations = 500;
        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            for (int iteration = 0; iteration < iterations; iteration++) {
                String id = "c-" + iteration;
                // Alternate the configured result so both outcomes are exercised.
                control.setDefaults(new Defaults(iteration % 2 == 0 ? Outcome.APPROVED : Outcome.DECLINED, 0));
                CyclicBarrier start = new CyclicBarrier(threads);
                List<Future<AuthorizationReply>> futures = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    futures.add(executor.submit(() -> {
                        start.await();
                        return authorize(id, PAYLOAD);
                    }));
                }
                List<AuthorizationReply.Authorized> replies = new ArrayList<>();
                for (Future<AuthorizationReply> future : futures) {
                    replies.add((AuthorizationReply.Authorized) future.get());
                }
                assertThat(replies).filteredOn(reply -> !reply.replayed()).hasSize(1);
                assertThat(replies).extracting(AuthorizationReply.Authorized::result).containsOnly(replies.getFirst().result());
                AuthorizationView view = control.authorization(id).orElseThrow();
                assertThat(view.invocations()).isEqualTo(threads);
                assertThat(view.result()).isEqualTo(replies.getFirst().result());
            }
        }
    }
}
