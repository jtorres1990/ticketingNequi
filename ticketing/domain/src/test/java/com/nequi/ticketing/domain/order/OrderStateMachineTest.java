package com.nequi.ticketing.domain.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nequi.ticketing.domain.error.InvalidStateTransitionException;
import com.nequi.ticketing.domain.error.ValidationException;
import com.nequi.ticketing.domain.order.Order.FunctionalCause;
import com.nequi.ticketing.domain.order.Order.OrderStatus;
import com.nequi.ticketing.domain.order.Order.PaymentOutcome;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class OrderStateMachineTest {

    private static final Instant RESERVED_AT = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    @DisplayName("VAL-012 and ST-006 create one ten-minute reservation")
    void createsOrderAndReservation() {
        Order order = order();

        assertThat(order.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(order.reservation().expiresAt()).isEqualTo(RESERVED_AT.plus(Duration.ofMinutes(10)));
        assertThat(order.activeLockHeld()).isTrue();
        assertThat(ActiveOrderKey.of(order)).isEqualTo(new ActiveOrderKey("customer", "event"));
    }

    @Test
    @DisplayName("ST-003/ST-007 confirm only an approved payment before expiry")
    void confirmsApprovedPaymentBeforeExpiry() {
        Order paid = order().startPayment(RESERVED_AT).recordPaymentOutcome(PaymentOutcome.APPROVED);
        Order confirmed = paid.confirm(paid.reservation().expiresAt().minusNanos(1));

        assertThat(paid.paymentAttempt().paymentAttemptId()).isEqualTo("order-1-1");
        assertThat(confirmed.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(confirmed.activeLockHeld()).isFalse();
        assertThatThrownBy(() -> confirmed.reject()).isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    @DisplayName("BR-029 cutoff is strict at fifteen seconds")
    void enforcesPaymentCutoffBoundary() {
        Instant expires = order().reservation().expiresAt();

        assertThat(order().startPayment(expires.minusSeconds(15).minusNanos(1)).paymentAttempt()).isNotNull();
        assertThatThrownBy(() -> order().startPayment(expires.minusSeconds(15)))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    @DisplayName("VAL-001 confirmation expiry boundary is strict")
    void neverConfirmsAtOrAfterExpiry() {
        Order paid = order().startPayment(RESERVED_AT).recordPaymentOutcome(PaymentOutcome.APPROVED);

        assertThatThrownBy(() -> paid.confirm(paid.reservation().expiresAt()))
                .isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> order().startPayment(RESERVED_AT).confirm(RESERVED_AT.plusSeconds(1)))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    @DisplayName("ST-008 rejects a payment and exposes PAYMENT_DECLINED")
    void rejectsOrder() {
        Order rejected = order().startPayment(RESERVED_AT)
                .recordPaymentOutcome(PaymentOutcome.DECLINED).reject();

        assertThat(rejected.status()).isEqualTo(OrderStatus.REJECTED);
        assertThat(rejected.failureCause()).isEqualTo(FunctionalCause.PAYMENT_DECLINED);
        assertThat(rejected.reversalPlan()).isNull();
        assertThatThrownBy(() -> order().startPayment(RESERVED_AT).reject())
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @ParameterizedTest(name = "{0} payment marks reversal: {1}")
    @MethodSource("processingFailureCases")
    @DisplayName("ST-009 marks reversal only for approved or unknown processing outcomes")
    void processingFailureReversalPolicy(PaymentOutcome outcome, boolean reversalExpected) {
        Order failed = order().startPayment(RESERVED_AT).recordPaymentOutcome(outcome).failProcessing(RESERVED_AT);

        assertThat(failed.status()).isEqualTo(OrderStatus.FAILED);
        assertThat(failed.failureCause()).isEqualTo(FunctionalCause.PROCESSING_FAILED);
        assertThat(failed.reversalPlan() != null).isEqualTo(reversalExpected);
    }

    @Test
    @DisplayName("BR-020 preserves a definitive PaymentAttempt outcome on repeated processing")
    void doesNotOverwriteDefinitivePaymentOutcome() {
        Order approved = order().startPayment(RESERVED_AT).recordPaymentOutcome(PaymentOutcome.APPROVED);

        assertThat(approved.recordPaymentOutcome(PaymentOutcome.APPROVED)).isEqualTo(approved);
        assertThatThrownBy(() -> approved.recordPaymentOutcome(PaymentOutcome.DECLINED))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    static Stream<Arguments> processingFailureCases() {
        return Stream.of(Arguments.of(PaymentOutcome.UNKNOWN, true), Arguments.of(PaymentOutcome.APPROVED, true),
                Arguments.of(PaymentOutcome.DECLINED, false), Arguments.of(PaymentOutcome.DEFINITIVE_ERROR, false));
    }

    @Test
    @DisplayName("ST-009 enqueue failure has no payment attempt or reversal")
    void failsEnqueue() {
        Order failed = order().failEnqueue();

        assertThat(failed.failureCause()).isEqualTo(FunctionalCause.PROCESSING_UNAVAILABLE);
        assertThat(failed.reversalPlan()).isNull();
        assertThatThrownBy(() -> order().startPayment(RESERVED_AT).failEnqueue())
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    @DisplayName("VAL-003/ST-010 expires only at the exact boundary or later")
    void expiresAtBoundaryAndMarksAttemptForReversal() {
        Order order = order();
        assertThatThrownBy(() -> order.expire(order.reservation().expiresAt().minusNanos(1)))
                .isInstanceOf(InvalidStateTransitionException.class);

        Order expired = order.startPayment(RESERVED_AT).expire(order.reservation().expiresAt());
        assertThat(expired.status()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(expired.failureCause()).isEqualTo(FunctionalCause.RESERVATION_EXPIRED);
        assertThat(expired.reversalPlan()).isNotNull();
    }

    @Test
    @DisplayName("ADR-025 quarantines an inconsistent CREATED Order and retains active lock")
    void quarantinesActiveOrder() {
        Order quarantined = order().quarantine(RESERVED_AT, "ticket condition failed");

        assertThat(quarantined.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(quarantined.activeLockHeld()).isTrue();
        assertThat(ReversalPolicy.quarantineOnTicketConditionFailure(quarantined)).isFalse();
        assertThat(ReversalPolicy.quarantineOnTicketConditionFailure(order())).isTrue();
        assertThatThrownBy(() -> quarantined.expire(quarantined.reservation().expiresAt()))
                .isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> quarantined.quarantine(RESERVED_AT, "again"))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    @DisplayName("ADR-025 reversal backoff follows 10s, 30s, 1m, 2m, 5m then 10m and exhausts at ten")
    void followsReversalSchedule() {
        assertThat(IntStream.range(0, 10).mapToObj(ReversalPlan::delayAfter).toList()).containsExactly(
                Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofMinutes(2),
                Duration.ofMinutes(5), Duration.ofMinutes(10), Duration.ofMinutes(10), Duration.ofMinutes(10),
                Duration.ofMinutes(10), Duration.ofMinutes(10));

        ReversalPlan plan = ReversalPlan.request("attempt", RESERVED_AT);
        for (int index = 0; index < 10; index++) {
            plan = plan.reschedule(RESERVED_AT.plusSeconds(index));
        }
        assertThat(plan.exhausted()).isTrue();
        ReversalPlan exhausted = plan;
        assertThatThrownBy(() -> exhausted.reschedule(RESERVED_AT)).isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> ReversalPlan.delayAfter(10)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ERR-010 validates ticket count, duplicates and idempotency key format")
    void validatesPurchaseRequest() {
        assertThatThrownBy(() -> request(List.of())).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> request(IntStream.range(0, 11).mapToObj(Integer::toString).toList()))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> request(List.of("A", "A"))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new PurchaseRequest("event", List.of("A"), "short"))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> request(java.util.Arrays.asList("A", null)))
                .isInstanceOf(ValidationException.class);
    }

    private static Order order() {
        return Order.create("order-1", "customer", request(List.of("A-1-1")), RESERVED_AT);
    }

    private static PurchaseRequest request(List<String> ids) {
        return new PurchaseRequest("event", ids, "valid_key_123456");
    }
}
