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

    /** Fixed Reservation duration (spec §13.1: not configurable). */
    public static final Duration RESERVATION_DURATION = Duration.ofMinutes(10);
    /** Approved payment cutoff margin ({@link OrderRules#DEPLOYED}); the effective value is configurable. */
    public static final Duration PAYMENT_CUTOFF = Duration.ofSeconds(15);

    public Order {
        orderId = required(orderId, "orderId");
        customerId = required(customerId, "customerId");
        eventId = required(eventId, "eventId");
        ticketIds = List.copyOf(required(ticketIds, "ticketIds"));
        status = required(status, "status");
        reservation = required(reservation, "reservation");
        if (ticketIds.isEmpty() || ticketIds.size() > OrderRules.ABSOLUTE_MAXIMUM_TICKETS
                || ticketIds.stream().distinct().count() != ticketIds.size()) {
            throw new IllegalArgumentException("an order must contain 1.."
                    + OrderRules.ABSOLUTE_MAXIMUM_TICKETS + " unique tickets");
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

    /** {@link #startPayment(Instant, Duration)} with the approved cutoff margin of 15 s. */
    public Order startPayment(Instant now) {
        return startPayment(now, PAYMENT_CUTOFF);
    }

    /** Starts the single PaymentAttempt; never inside the configurable cutoff margin before expiry (BR-029). */
    public Order startPayment(Instant now, Duration paymentCutoff) {
        requireActive();
        if (paymentAttempt != null) {
            throw new InvalidStateTransitionException("an Order has at most one payment attempt");
        }
        Instant cutoff = required(now, "now").plus(required(paymentCutoff, "paymentCutoff"));
        if (!reservation.expiresAt().isAfter(cutoff)) {
            throw new InvalidStateTransitionException("payment cannot start inside the cutoff margin");
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

    /**
     * ST-009 (processing): a definitive technical failure, with or without a PaymentAttempt (ADR-029:
     * "with reversal if there was a PaymentAttempt of unknown outcome").
     */
    public Order failProcessing(Instant now) {
        requireActive();
        return terminalWithPossibleReversal(OrderStatus.FAILED, FunctionalCause.PROCESSING_FAILED, required(now, "now"));
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

    /**
     * ADR-008 / ADR-025 (AP-032): an approval that arrives for an Order already closed without
     * confirmation never reopens it; the reversal is marked unless it is already pending or completed.
     */
    public Order recordLateApproval(Instant now) {
        if (status != OrderStatus.EXPIRED && status != OrderStatus.FAILED && status != OrderStatus.REJECTED) {
            throw new InvalidStateTransitionException("a late approval applies only to an Order closed without confirmation");
        }
        requirePaymentAttempt();
        if (reversalPlan != null) {
            return this;
        }
        return copy(status, failureCause, paymentAttempt, quarantinedAt, quarantineReason,
                ReversalPlan.request(paymentAttempt.paymentAttemptId(), required(now, "now")));
    }

    /** True when {@link #recordLateApproval(Instant)} marks a new reversal. */
    public boolean lateApprovalMarksReversal() {
        return reversalPlan == null;
    }

    /** AP-030 "complete": the provider confirmed the cancellation of the PaymentAttempt. */
    public Order completeReversal(Instant now) {
        return copy(status, failureCause, paymentAttempt, quarantinedAt, quarantineReason,
                requireReversal().complete(now));
    }

    /** {@link #rescheduleReversal(Instant, ReversalSchedule)} with the approved schedule. */
    public Order rescheduleReversal(Instant now) {
        return rescheduleReversal(now, ReversalSchedule.DEPLOYED);
    }

    /** AP-030 "reschedule" or "exhaust": one more transient cancellation failure (ADR-025). */
    public Order rescheduleReversal(Instant now, ReversalSchedule schedule) {
        return copy(status, failureCause, paymentAttempt, quarantinedAt, quarantineReason,
                requireReversal().reschedule(now, schedule));
    }

    public boolean reversalPending() {
        return reversalPlan != null && reversalPlan.pending();
    }

    public boolean activeLockHeld() {
        return status == OrderStatus.CREATED;
    }

    private ReversalPlan requireReversal() {
        if (reversalPlan == null) {
            throw new InvalidStateTransitionException("the Order has no payment reversal");
        }
        return reversalPlan;
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
