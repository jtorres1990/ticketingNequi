package com.nequi.ticketing.domain.order;

import static com.nequi.ticketing.domain.shared.DomainChecks.required;

import com.nequi.ticketing.domain.error.InvalidStateTransitionException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

public record Order(
        String orderId,
        String customerId,
        String eventId,
        List<String> ticketIds,
        OrderStatus status,
        FunctionalCause failureCause,
        Reservation reservation,
        PaymentAttempt paymentAttempt,
        Instant quarantinedAt,
        String quarantineReason,
        ReversalPlan reversalPlan) {

    public static final Duration RESERVATION_DURATION = Duration.ofMinutes(10);
    public static final Duration PAYMENT_CUTOFF = Duration.ofSeconds(15);

    public Order {
        orderId = required(orderId, "orderId");
        customerId = required(customerId, "customerId");
        eventId = required(eventId, "eventId");
        ticketIds = List.copyOf(required(ticketIds, "ticketIds"));
        status = required(status, "status");
        reservation = required(reservation, "reservation");
        if (ticketIds.isEmpty() || ticketIds.size() > 10 || ticketIds.stream().distinct().count() != ticketIds.size()) {
            throw new IllegalArgumentException("an order must contain 1..10 unique tickets");
        }
        if ((quarantinedAt == null) != (quarantineReason == null)) {
            throw new IllegalArgumentException("quarantine time and reason must be set together");
        }
    }

    public static Order create(String orderId, String customerId, PurchaseRequest request, Instant reservedAt) {
        required(request, "purchaseRequest");
        Instant start = required(reservedAt, "reservedAt");
        Reservation reservation = new Reservation(orderId, start, start.plus(RESERVATION_DURATION));
        return new Order(orderId, customerId, request.eventId(), request.ticketIds(), OrderStatus.CREATED,
                null, reservation, null, null, null, null);
    }

    public Order startPayment(Instant now) {
        requireActive();
        if (paymentAttempt != null) {
            throw new InvalidStateTransitionException("an Order has at most one payment attempt");
        }
        Instant cutoff = required(now, "now").plus(PAYMENT_CUTOFF);
        if (!reservation.expiresAt().isAfter(cutoff)) {
            throw new InvalidStateTransitionException("payment cannot start inside the 15 second cutoff");
        }
        return copy(status, failureCause, new PaymentAttempt(orderId + "-1", PaymentOutcome.UNKNOWN, now),
                quarantinedAt, quarantineReason, reversalPlan);
    }

    public Order recordPaymentOutcome(PaymentOutcome outcome) {
        requireActive();
        if (paymentAttempt == null) {
            throw new InvalidStateTransitionException("payment has not started");
        }
        if (paymentAttempt.outcome() != PaymentOutcome.UNKNOWN && paymentAttempt.outcome() != outcome) {
            throw new InvalidStateTransitionException("a definitive payment outcome cannot be overwritten");
        }
        return copy(status, failureCause, paymentAttempt.withOutcome(outcome), quarantinedAt, quarantineReason, reversalPlan);
    }

    public Order confirm(Instant now) {
        requireActive();
        requirePaymentAttempt();
        if (paymentAttempt.outcome() != PaymentOutcome.APPROVED) {
            throw new InvalidStateTransitionException("only an approved payment can confirm an Order");
        }
        if (!reservation.expiresAt().isAfter(required(now, "now"))) {
            throw new InvalidStateTransitionException("an expired Order cannot be confirmed");
        }
        return copy(OrderStatus.CONFIRMED, null, paymentAttempt, null, null, null);
    }

    public Order reject() {
        requireActive();
        requirePaymentAttempt();
        if (paymentAttempt.outcome() != PaymentOutcome.DECLINED) {
            throw new InvalidStateTransitionException("only a declined payment can reject an Order");
        }
        return copy(OrderStatus.REJECTED, FunctionalCause.PAYMENT_DECLINED, paymentAttempt, null, null, null);
    }

    public Order failProcessing(Instant now) {
        requireActive();
        requirePaymentAttempt();
        return terminalWithPossibleReversal(OrderStatus.FAILED, FunctionalCause.PROCESSING_FAILED, now);
    }

    public Order failEnqueue() {
        requireActive();
        if (paymentAttempt != null) {
            throw new InvalidStateTransitionException("enqueue failure requires no payment attempt");
        }
        return copy(OrderStatus.FAILED, FunctionalCause.PROCESSING_UNAVAILABLE, null, null, null, null);
    }

    public Order expire(Instant now) {
        requireActive();
        Instant current = required(now, "now");
        if (reservation.expiresAt().isAfter(current)) {
            throw new InvalidStateTransitionException("only an expired reservation can expire");
        }
        return terminalWithPossibleReversal(OrderStatus.EXPIRED, FunctionalCause.RESERVATION_EXPIRED, current);
    }

    public Order quarantine(Instant now, String reason) {
        requireActive();
        if (quarantinedAt != null) {
            throw new InvalidStateTransitionException("Order is already quarantined");
        }
        return copy(status, failureCause, paymentAttempt, required(now, "now"), required(reason, "reason"), reversalPlan);
    }

    public boolean activeLockHeld() {
        return status == OrderStatus.CREATED;
    }

    private Order terminalWithPossibleReversal(OrderStatus target, FunctionalCause cause, Instant now) {
        ReversalPlan reversal = ReversalPolicy.shouldMark(target, paymentAttempt)
                ? ReversalPlan.request(paymentAttempt.paymentAttemptId(), now)
                : null;
        return copy(target, cause, paymentAttempt, null, null, reversal);
    }

    private void requireActive() {
        if (status != OrderStatus.CREATED || quarantinedAt != null) {
            throw new InvalidStateTransitionException("only a non-quarantined CREATED Order can transition");
        }
    }

    private void requirePaymentAttempt() {
        if (paymentAttempt == null) {
            throw new InvalidStateTransitionException("payment attempt is required");
        }
    }

    private Order copy(
            OrderStatus newStatus,
            FunctionalCause newCause,
            PaymentAttempt newAttempt,
            Instant newQuarantinedAt,
            String newQuarantineReason,
            ReversalPlan newReversal) {
        return new Order(orderId, customerId, eventId, ticketIds, newStatus, newCause, reservation,
                newAttempt, newQuarantinedAt, newQuarantineReason, newReversal);
    }

    public record Reservation(String reservationId, Instant reservedAt, Instant expiresAt) {
        public Reservation {
            reservationId = required(reservationId, "reservationId");
            reservedAt = required(reservedAt, "reservedAt");
            expiresAt = required(expiresAt, "expiresAt");
            if (!expiresAt.equals(reservedAt.plus(RESERVATION_DURATION))) {
                throw new IllegalArgumentException("a reservation lasts exactly ten minutes");
            }
        }
    }

    public record PaymentAttempt(String paymentAttemptId, PaymentOutcome outcome, Instant startedAt) {
        public PaymentAttempt {
            paymentAttemptId = required(paymentAttemptId, "paymentAttemptId");
            outcome = required(outcome, "paymentOutcome");
            startedAt = required(startedAt, "paymentStartedAt");
        }

        PaymentAttempt withOutcome(PaymentOutcome newOutcome) {
            return new PaymentAttempt(paymentAttemptId, required(newOutcome, "paymentOutcome"), startedAt);
        }
    }

    public enum OrderStatus {
        CREATED,
        CONFIRMED,
        REJECTED,
        FAILED,
        EXPIRED;

        public boolean terminal() {
            return this != CREATED;
        }
    }

    public enum PaymentOutcome {
        UNKNOWN,
        APPROVED,
        DECLINED,
        DEFINITIVE_ERROR
    }

    public enum FunctionalCause {
        PAYMENT_DECLINED,
        PROCESSING_UNAVAILABLE,
        PROCESSING_FAILED,
        RESERVATION_EXPIRED
    }
}
