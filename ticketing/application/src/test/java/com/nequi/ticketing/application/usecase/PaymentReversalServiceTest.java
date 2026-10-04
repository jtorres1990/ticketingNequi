package com.nequi.ticketing.application.usecase;

import static com.nequi.ticketing.application.usecase.WorkerFixture.CUSTOMER_A;
import static com.nequi.ticketing.application.usecase.WorkerFixture.NOW;
import static com.nequi.ticketing.application.usecase.WorkerFixture.key;
import static org.assertj.core.api.Assertions.assertThat;

import com.nequi.ticketing.application.port.in.CycleResult;
import com.nequi.ticketing.application.port.in.ItemOutcome;
import com.nequi.ticketing.application.port.out.AuthorizationOutcome;
import com.nequi.ticketing.application.port.out.CancellationOutcome;
import com.nequi.ticketing.application.port.out.FailedItem;
import com.nequi.ticketing.application.port.out.ItemFailure;
import com.nequi.ticketing.application.port.out.OrderRecord;
import com.nequi.ticketing.application.port.out.TransactionOutcome;
import com.nequi.ticketing.application.testdouble.InMemoryTicketingStore.Operation;
import com.nequi.ticketing.domain.audit.AuditRecord;
import com.nequi.ticketing.domain.audit.AuditRecord.AuditCode;
import com.nequi.ticketing.domain.order.Order;
import com.nequi.ticketing.domain.order.Order.OrderStatus;
import com.nequi.ticketing.domain.order.ReversalPlan;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentReversalServiceTest {

    private WorkerFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new WorkerFixture();
        fixture.seedEvent();
    }

    /** An Order closed FAILED on the last reception with an unknown payment outcome (reversal marked). */
    private Order failedWithReversal() {
        Order order = fixture.createOrder(CUSTOMER_A, key(1), "A-1-1");
        fixture.gateway.scriptAuthorizations(new AuthorizationOutcome.DependencyUnavailable("timeout"));
        fixture.process(order.orderId(), 5, true);
        return fixture.order(order.orderId()).order();
    }

    @Test
    @DisplayName("FR-023 BR-028 ALT-008 MF-005 a confirmed cancellation completes the reversal with evidence and keeps the terminal state")
    void confirmedCancellationCompletesTheReversal() {
        Order failed = failedWithReversal();

        CycleResult result = fixture.reverse();

        OrderRecord stored = fixture.order(failed.orderId());
        assertThat(result.count(ItemOutcome.REVERSAL_CONFIRMED)).isEqualTo(1);
        assertThat(stored.order().status()).isEqualTo(OrderStatus.FAILED);
        assertThat(stored.order().reversalPending()).isFalse();
        assertThat(stored.order().reversalPlan().completedAt()).isEqualTo(NOW);
        assertThat(fixture.gateway.cancellationCalls()).containsExactly(failed.orderId() + "-1");
        AuditRecord audit = fixture.store.audits().getLast();
        assertThat(audit.code()).isEqualTo(AuditCode.PAYMENT_REVERSAL_CONFIRMED);
        assertThat(audit.cause()).isEqualTo("REGISTERED_BEFORE_CHARGE");
        assertThat(audit.paymentAttemptId()).isEqualTo(failed.orderId() + "-1");
        assertThat(fixture.reverse().total()).isZero();
        assertThat(fixture.gateway.cancellationCalls()).hasSize(1);
    }

    @Test
    @DisplayName("ADR-025 a transient cancellation failure reschedules the reversal with the persisted backoff")
    void transientFailureReschedules() {
        Order failed = failedWithReversal();
        fixture.gateway.scriptCancellations(new CancellationOutcome.DependencyUnavailable("5xx"));

        CycleResult result = fixture.reverse();

        ReversalPlan plan = fixture.order(failed.orderId()).order().reversalPlan();
        assertThat(result.count(ItemOutcome.REVERSAL_RESCHEDULED)).isEqualTo(1);
        assertThat(plan.attempts()).isEqualTo(1);
        assertThat(plan.nextAttemptAt()).isEqualTo(NOW.plusSeconds(10));
        assertThat(fixture.reverse().total()).isZero();

        fixture.clock.advance(Duration.ofSeconds(10));
        assertThat(fixture.reverse().count(ItemOutcome.REVERSAL_CONFIRMED)).isEqualTo(1);
    }

    @Test
    @DisplayName("ERR-017 ADR-025 the tenth failed cancellation exhausts the reversal for manual review and alerts through its audit")
    void tenthFailureExhausts() {
        Order failed = failedWithReversal();
        for (int attempt = 1; attempt <= ReversalPlan.MAXIMUM_ATTEMPTS; attempt++) {
            fixture.gateway.scriptCancellations(new CancellationOutcome.ContractError(401));
            CycleResult result = fixture.reverse();
            assertThat(result.total()).isEqualTo(1);
            fixture.clock.advance(Duration.ofMinutes(10));
        }

        Order stored = fixture.order(failed.orderId()).order();
        assertThat(stored.status()).isEqualTo(OrderStatus.FAILED);
        assertThat(stored.reversalPlan().exhausted()).isTrue();
        assertThat(stored.reversalPending()).isTrue();
        assertThat(fixture.gateway.cancellationCalls()).hasSize(ReversalPlan.MAXIMUM_ATTEMPTS);
        assertThat(fixture.store.audits().getLast().code()).isEqualTo(AuditCode.PAYMENT_REVERSAL_EXHAUSTED);
        assertThat(fixture.reverse().total()).isZero();
    }

    @Test
    @DisplayName("ADR-030 a gateway error or an empty result is a transient failure")
    void gatewayErrorsAreTransient() {
        Order order = failedWithReversal();
        PaymentReversalService broken = new PaymentReversalService(fixture.store, fixture.store,
                new com.nequi.ticketing.application.port.out.PaymentGateway() {
                    @Override
                    public reactor.core.publisher.Mono<AuthorizationOutcome> authorize(
                            com.nequi.ticketing.application.port.out.PaymentAuthorization request, java.time.Instant deadline) {
                        return reactor.core.publisher.Mono.empty();
                    }

                    @Override
                    public reactor.core.publisher.Mono<CancellationOutcome> cancel(String paymentAttemptId) {
                        return paymentAttemptId.isEmpty()
                                ? reactor.core.publisher.Mono.empty()
                                : reactor.core.publisher.Mono.error(new IllegalStateException("down"));
                    }
                }, fixture.clock, fixture.settings);

        CycleResult result = Rejections.value(broken.reverseDue(
                com.nequi.ticketing.application.port.in.CycleRequest.of("c")));

        assertThat(result.count(ItemOutcome.REVERSAL_RESCHEDULED)).isEqualTo(1);
        assertThat(fixture.order(order.orderId()).order().reversalPlan().attempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("ADR-025 concurrent reversal processes: conditions make a second completion or reschedule not applicable")
    void concurrentUpdatesAreNotApplicable() {
        Order failed = failedWithReversal();
        fixture.store.script(Operation.COMPLETE_REVERSAL,
                TransactionOutcome.cancelled(List.of(ItemFailure.of(FailedItem.ORDER))), TransactionOutcome.conflict());

        assertThat(fixture.reverse().count(ItemOutcome.NOT_APPLICABLE)).isEqualTo(1);
        assertThat(fixture.reverse().count(ItemOutcome.FAILED)).isEqualTo(1);

        fixture.gateway.scriptCancellations(new CancellationOutcome.DependencyUnavailable("timeout"));
        Order stored = fixture.order(failed.orderId()).order();
        fixture.store.beforeNext(Operation.RESCHEDULE_REVERSAL, () -> fixture.store.putOrder(new OrderRecord(
                stored.rescheduleReversal(NOW), NOW, NOW, null)));
        assertThat(fixture.reverse().count(ItemOutcome.NOT_APPLICABLE)).isEqualTo(1);
    }

    @Test
    @DisplayName("ERR-017 exhaustion conditions: a lost or conflicting exhaustion is retried by the next cycle")
    void exhaustionOutcomes() {
        Order failed = failedWithReversal();
        Order nearlyExhausted = failed;
        for (int attempt = 1; attempt < ReversalPlan.MAXIMUM_ATTEMPTS; attempt++) {
            nearlyExhausted = nearlyExhausted.rescheduleReversal(NOW);
        }
        fixture.store.putOrder(new OrderRecord(nearlyExhausted, NOW, NOW, null));
        fixture.clock.advance(Duration.ofHours(1));
        fixture.store.script(Operation.EXHAUST_REVERSAL,
                TransactionOutcome.cancelled(List.of(ItemFailure.of(FailedItem.ORDER))), TransactionOutcome.conflict());

        fixture.gateway.scriptCancellations(new CancellationOutcome.DependencyUnavailable("timeout"));
        assertThat(fixture.reverse().count(ItemOutcome.NOT_APPLICABLE)).isEqualTo(1);
        fixture.gateway.scriptCancellations(new CancellationOutcome.DependencyUnavailable("timeout"));
        assertThat(fixture.reverse().count(ItemOutcome.FAILED)).isEqualTo(1);
    }

    @Test
    @DisplayName("FG-003 an index entry for an Order whose reversal is not due or not pending is ignored")
    void staleReversalEntriesAreIgnored() {
        Order failed = failedWithReversal();
        fixture.store.beforeNext(Operation.FIND_ORDER, () -> fixture.store.putOrder(new OrderRecord(
                failed.completeReversal(NOW), NOW, NOW, null)));

        assertThat(fixture.reverse().count(ItemOutcome.NOT_APPLICABLE)).isEqualTo(1);
        assertThat(fixture.gateway.cancellationCalls()).isEmpty();
    }
}
