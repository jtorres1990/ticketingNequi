package com.nequi.ticketing.application.port.out;

import com.nequi.ticketing.domain.order.ReversalPlan;
import java.time.Instant;
import reactor.core.publisher.Mono;

/**
 * Outbound port: atomic Order transitions of ADR-025 with typed results (ADR-034): reserve and create,
 * start payment, claim lease, confirm, close with release (lock removal and reversal mark), mark
 * {@code enqueuedAt}, quarantine, late approval and the payment reversal operations. A condition that
 * is not met is a {@link TransactionOutcome.Cancelled} result (or {@code false} for single conditional
 * writes), never an error.
 */
public interface OrderLifecycleStore {

    /** AP-008: reserve and create the Order (14 items at most). */
    Mono<TransactionOutcome> reserve(ReservationPlan plan);

    /**
     * AP-011: set {@code enqueuedAt} and leave the pending-enqueue index. Conditional on
     * {@code status = CREATED} and no {@code enqueuedAt}; emits {@code false} when the condition fails.
     */
    Mono<Boolean> markEnqueued(String orderId, Instant enqueuedAt);

    /** AP-015 "Fail (enqueue)" (13 items at most). */
    Mono<TransactionOutcome> failEnqueue(EnqueueFailurePlan plan);

    /** AP-031: quarantine an Order whose transition was cancelled by a Ticket condition. */
    Mono<TransactionOutcome> quarantine(QuarantinePlan plan);

    /** AP-012: open the PaymentAttempt with its lease and move the Tickets to PENDING_CONFIRMATION (12 items at most). */
    Mono<TransactionOutcome> startPayment(PaymentStartPlan plan);

    /**
     * AP-013: claim the lease of the active PaymentAttempt. Conditional on {@code status = CREATED}, same
     * {@code paymentAttemptId}, {@code paymentLeaseUntilMs < now} and no quarantine; emits {@code false}
     * when the condition fails.
     */
    Mono<Boolean> claimPaymentLease(String orderId, String paymentAttemptId, PaymentLease lease, Instant now);

    /** AP-014: confirm the Order and sell its Tickets (13 items at most). */
    Mono<TransactionOutcome> confirm(ConfirmationPlan plan);

    /** AP-015 "Reject", "Fail (processing)" and "Expire" with release (13 items at most). */
    Mono<TransactionOutcome> close(ClosurePlan plan);

    /** AP-032: record a late approval that was not applied and ensure the reversal mark (2 items). */
    Mono<TransactionOutcome> recordLateApproval(LateApprovalPlan plan);

    /** AP-030 "complete": the cancellation was confirmed (2 items). */
    Mono<TransactionOutcome> completeReversal(ReversalCompletionPlan plan);

    /** AP-030 "exhaust": the reversal is left for manual review (2 items). */
    Mono<TransactionOutcome> exhaustReversal(ReversalExhaustionPlan plan);

    /**
     * AP-030 "reschedule": store the next attempt of a pending reversal. Conditional on the reversal
     * being pending with {@code paymentReversalAttempts = expectedAttempts}; emits {@code false} when the
     * condition fails.
     */
    Mono<Boolean> rescheduleReversal(String orderId, int expectedAttempts, ReversalPlan next);
}
