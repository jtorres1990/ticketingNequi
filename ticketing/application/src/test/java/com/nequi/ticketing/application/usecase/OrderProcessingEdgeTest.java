package com.nequi.ticketing.application.usecase;

import static com.nequi.ticketing.application.usecase.WorkerFixture.CUSTOMER_A;
import static com.nequi.ticketing.application.usecase.WorkerFixture.EVENT_ID;
import static com.nequi.ticketing.application.usecase.WorkerFixture.NOW;
import static com.nequi.ticketing.application.usecase.WorkerFixture.key;
import static org.assertj.core.api.Assertions.assertThat;

import com.nequi.ticketing.application.port.in.DispositionReason;
import com.nequi.ticketing.application.port.in.MessageDisposition;
import com.nequi.ticketing.application.port.out.AuthorizationOutcome;
import com.nequi.ticketing.application.port.out.FailedItem;
import com.nequi.ticketing.application.port.out.ItemFailure;
import com.nequi.ticketing.application.port.out.OrderRecord;
import com.nequi.ticketing.application.port.out.TransactionOutcome;
import com.nequi.ticketing.application.testdouble.InMemoryTicketingStore.Operation;
import com.nequi.ticketing.domain.order.Order;
import com.nequi.ticketing.domain.order.Order.OrderStatus;
import com.nequi.ticketing.domain.ticket.Ticket.TicketState;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Defensive paths of CMP-007 (ADR-025 "re-read and re-evaluate", ADR-029 transient handling, ADR-035
 * conflicts): every unexpected store or provider outcome ends in a typed disposition, never in an
 * error signal towards the consumer adapter.
 */
class OrderProcessingEdgeTest {

    private static final TransactionOutcome ORDER_CONDITION =
            TransactionOutcome.cancelled(java.util.List.of(ItemFailure.of(FailedItem.ORDER)));

    private WorkerFixture fixture;
    private Order order;

    @BeforeEach
    void setUp() {
        fixture = new WorkerFixture();
        fixture.seedEvent();
        order = fixture.createOrder(CUSTOMER_A, key(1), "A-1-1");
    }

    private TransactionOutcome[] repeated(TransactionOutcome outcome, int times) {
        return IntStream.range(0, times).mapToObj(index -> outcome).toArray(TransactionOutcome[]::new);
    }

    @Test
    @DisplayName("ADR-025 endless condition failures are bounded by the re-evaluation limit and treated as transient")
    void reevaluationIsBounded() {
        fixture.store.script(Operation.START_PAYMENT, repeated(ORDER_CONDITION, 10));

        assertThat(fixture.process(order.orderId())).isEqualTo(MessageDisposition.retry(DispositionReason.TRANSIENT_FAILURE));
        assertThat(fixture.order(order.orderId()).order().paymentAttempt()).isNull();
    }

    @Test
    @DisplayName("ERR-005 an Order that disappears between the cancellation and the re-read is poison")
    void vanishedOrderAfterCancellation() {
        fixture.store.script(Operation.START_PAYMENT, ORDER_CONDITION);
        fixture.store.beforeNext(Operation.START_PAYMENT, () -> fixture.store.removeOrder(order.orderId()));

        assertThat(fixture.process(order.orderId())).isEqualTo(MessageDisposition.poison(DispositionReason.ENTITY_NOT_FOUND));
    }

    @Test
    @DisplayName("ADR-025 a quarantine whose condition fails is re-evaluated; a quarantine conflict is transient")
    void quarantineOutcomes() {
        fixture.store.setTicket(EVENT_ID, "A-1-1", TicketState.RESERVED, "someone-else");
        fixture.store.script(Operation.QUARANTINE, ORDER_CONDITION);

        assertThat(fixture.process(order.orderId()))
                .isEqualTo(MessageDisposition.delete(DispositionReason.QUARANTINE_APPLIED));

        Order second = fixture.createOrder("customer-z", key(2), "A-1-2");
        fixture.store.setTicket(EVENT_ID, "A-1-2", TicketState.RESERVED, "someone-else");
        fixture.store.script(Operation.QUARANTINE, TransactionOutcome.conflict());
        assertThat(fixture.process(second.orderId()))
                .isEqualTo(MessageDisposition.retry(DispositionReason.TRANSIENT_FAILURE));
    }

    @Test
    @DisplayName("ADR-030 an error raised by the gateway is a transient result")
    void gatewayErrorIsTransient() {
        fixture.gateway.onAuthorize(() -> {
            throw new IllegalStateException("connection reset");
        });

        assertThat(fixture.process(order.orderId())).isEqualTo(MessageDisposition.retry(DispositionReason.TRANSIENT_FAILURE));
    }

    @Test
    @DisplayName("ADR-035 conflicts while confirming, rejecting or recording a late approval are transient")
    void transitionConflicts() {
        fixture.store.script(Operation.CONFIRM, TransactionOutcome.conflict());
        assertThat(fixture.process(order.orderId())).isEqualTo(MessageDisposition.retry(DispositionReason.TRANSIENT_FAILURE));

        fixture.clock.advance(java.time.Duration.ofSeconds(46));
        fixture.gateway.scriptAuthorizations();
        Order rejected = fixture.order(order.orderId()).order().recordPaymentOutcome(Order.PaymentOutcome.DECLINED).reject();
        fixture.store.beforeNext(Operation.CONFIRM, () -> fixture.store.putOrder(new OrderRecord(rejected, NOW, NOW, NOW)));
        fixture.store.script(Operation.LATE_APPROVAL, TransactionOutcome.conflict());
        assertThat(fixture.process(order.orderId())).isEqualTo(MessageDisposition.retry(DispositionReason.TRANSIENT_FAILURE));
    }

    @Test
    @DisplayName("ADR-008 a late approval already recorded leaves the terminal Order untouched")
    void lateApprovalAlreadyRecorded() {
        Order rejected = order.startPayment(NOW).recordPaymentOutcome(Order.PaymentOutcome.DECLINED).reject();
        fixture.store.beforeNext(Operation.CONFIRM, () -> fixture.store.putOrder(new OrderRecord(rejected, NOW, NOW, NOW)));
        fixture.store.script(Operation.LATE_APPROVAL, ORDER_CONDITION);

        assertThat(fixture.process(order.orderId())).isEqualTo(MessageDisposition.delete(DispositionReason.ALREADY_TERMINAL));
    }

    @Test
    @DisplayName("ADR-008 a confirmation lost because expiresAt passed meanwhile expires the Order with the late approval")
    void confirmationLostToTime() {
        fixture.store.beforeNext(Operation.CONFIRM, () -> fixture.clock.set(order.reservation().expiresAt().plusSeconds(1)));
        fixture.store.script(Operation.CONFIRM, ORDER_CONDITION);

        assertThat(fixture.process(order.orderId())).isEqualTo(MessageDisposition.delete(DispositionReason.EXPIRED));
        assertThat(fixture.order(order.orderId()).order().reversalPending()).isTrue();
    }

    @Test
    @DisplayName("ADR-008 ADR-025 repeated cancellations of the late-approval expiration are bounded and treated as transient")
    void lateApprovalExpirationOutcomes() {
        fixture.store.beforeNext(Operation.CONFIRM, () -> fixture.clock.set(order.reservation().expiresAt().plusSeconds(1)));
        fixture.store.script(Operation.CONFIRM, ORDER_CONDITION);
        fixture.store.script(Operation.CLOSE, repeated(ORDER_CONDITION, 10));
        assertThat(fixture.process(order.orderId())).isEqualTo(MessageDisposition.retry(DispositionReason.TRANSIENT_FAILURE));
        assertThat(fixture.order(order.orderId()).order().status()).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    @DisplayName("ADR-035 a conflict on the late-approval expiration is transient")
    void lateApprovalExpirationConflict() {
        fixture.store.beforeNext(Operation.CONFIRM, () -> fixture.clock.set(order.reservation().expiresAt().plusSeconds(1)));
        fixture.store.script(Operation.CONFIRM, ORDER_CONDITION);
        fixture.store.script(Operation.CLOSE, TransactionOutcome.conflict());

        assertThat(fixture.process(order.orderId())).isEqualTo(MessageDisposition.retry(DispositionReason.TRANSIENT_FAILURE));
    }

    @Test
    @DisplayName("ERR-005 an Order that disappears after a cancelled confirmation is poison")
    void vanishedOrderAfterCancelledConfirmation() {
        fixture.store.beforeNext(Operation.CONFIRM, () -> fixture.store.removeOrder(order.orderId()));
        fixture.store.script(Operation.CONFIRM, ORDER_CONDITION);

        assertThat(fixture.process(order.orderId())).isEqualTo(MessageDisposition.poison(DispositionReason.ENTITY_NOT_FOUND));
    }

    @Test
    @DisplayName("ADR-035 a conflict while rejecting is transient")
    void rejectionConflict() {
        fixture.gateway.scriptAuthorizations(new AuthorizationOutcome.Declined("ref", "DECLINED"));
        fixture.store.script(Operation.CLOSE, TransactionOutcome.conflict());

        assertThat(fixture.process(order.orderId())).isEqualTo(MessageDisposition.retry(DispositionReason.TRANSIENT_FAILURE));
    }

    @Test
    @DisplayName("ADR-029 on the last reception a re-read showing a terminal, quarantined or missing Order is not failed again")
    void lastReceptionRereadOutcomes() {
        Order terminal = order.failProcessing(NOW);
        fixture.store.failNext(Operation.START_PAYMENT, 1);
        fixture.store.beforeNext(Operation.START_PAYMENT, () -> fixture.store.putOrder(new OrderRecord(terminal, NOW, NOW, NOW)));
        assertThat(fixture.process(order.orderId(), 5, true))
                .isEqualTo(MessageDisposition.delete(DispositionReason.ALREADY_TERMINAL));

        fixture.store.putOrder(new OrderRecord(order, NOW, NOW, NOW));
        fixture.store.failNext(Operation.START_PAYMENT, 1);
        fixture.store.beforeNext(Operation.START_PAYMENT, () -> fixture.store.putOrder(new OrderRecord(
                order.quarantine(NOW, "review"), NOW, NOW, NOW)));
        assertThat(fixture.process(order.orderId(), 5, true)).isEqualTo(MessageDisposition.delete(DispositionReason.QUARANTINED));

        fixture.store.putOrder(new OrderRecord(order, NOW, NOW, NOW));
        fixture.store.failNext(Operation.START_PAYMENT, 1);
        fixture.store.beforeNext(Operation.START_PAYMENT, () -> fixture.store.removeOrder(order.orderId()));
        assertThat(fixture.process(order.orderId(), 5, true))
                .isEqualTo(MessageDisposition.poison(DispositionReason.ENTITY_NOT_FOUND));
    }

    @Test
    @DisplayName("ADR-029 RISK-014 on the last reception a failed FAILED transition leaves the message for the DLQ")
    void exhaustionOutcomes() {
        fixture.gateway.scriptAuthorizations(new AuthorizationOutcome.DependencyUnavailable("timeout"));
        fixture.store.script(Operation.CLOSE, TransactionOutcome.conflict());
        assertThat(fixture.process(order.orderId(), 5, true))
                .isEqualTo(MessageDisposition.retry(DispositionReason.TRANSIENT_FAILURE));

        fixture.store.script(Operation.CLOSE, ORDER_CONDITION);
        fixture.clock.advance(java.time.Duration.ofSeconds(46));
        fixture.gateway.scriptAuthorizations(new AuthorizationOutcome.DependencyUnavailable("timeout"));
        assertThat(fixture.process(order.orderId(), 5, true))
                .isEqualTo(MessageDisposition.retry(DispositionReason.TRANSIENT_FAILURE));
        assertThat(fixture.order(order.orderId()).order().status()).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    @DisplayName("ADR-025 ADR-029 a cancelled last-reception failure: quarantine not applied, terminal, quarantined or missing Order")
    void cancelledExhaustionBranches() {
        fixture.gateway.scriptAuthorizations(new AuthorizationOutcome.DependencyUnavailable("timeout"));
        fixture.store.beforeNext(Operation.CLOSE, () -> fixture.store.removeTicket(EVENT_ID, "A-1-1"));
        fixture.store.script(Operation.QUARANTINE, ORDER_CONDITION);
        assertThat(fixture.process(order.orderId(), 5, true))
                .isEqualTo(MessageDisposition.retry(DispositionReason.TRANSIENT_FAILURE));

        Order started = fixture.order(order.orderId()).order();
        fixture.clock.advance(java.time.Duration.ofSeconds(46));
        assertExhaustionAfter(() -> fixture.store.putOrder(new OrderRecord(started.failProcessing(NOW), NOW, NOW, NOW)),
                MessageDisposition.delete(DispositionReason.ALREADY_TERMINAL), started);
        assertExhaustionAfter(() -> fixture.store.putOrder(new OrderRecord(started.quarantine(NOW, "review"), NOW, NOW, NOW)),
                MessageDisposition.delete(DispositionReason.QUARANTINED), started);
        assertExhaustionAfter(() -> fixture.store.removeOrder(order.orderId()),
                MessageDisposition.poison(DispositionReason.ENTITY_NOT_FOUND), started);
    }

    private void assertExhaustionAfter(Runnable concurrentChange, MessageDisposition expected, Order started) {
        fixture.store.putOrder(new OrderRecord(started, NOW, NOW, NOW));
        fixture.gateway.scriptAuthorizations(new AuthorizationOutcome.DependencyUnavailable("timeout"));
        fixture.store.script(Operation.CLOSE, ORDER_CONDITION);
        fixture.store.beforeNext(Operation.CLOSE, concurrentChange);

        assertThat(fixture.process(order.orderId(), 5, true)).isEqualTo(expected);
    }
}
