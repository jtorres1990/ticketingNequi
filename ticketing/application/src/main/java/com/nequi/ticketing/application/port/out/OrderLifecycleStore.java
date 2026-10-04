package com.nequi.ticketing.application.port.out;

import java.time.Instant;
import reactor.core.publisher.Mono;

/**
 * Outbound port: atomic Order transitions of ADR-025 used by the {@code api} role, with typed results
 * (ADR-034). Worker transitions (start payment, lease, confirm, close, late approval, reversal) are
 * added with their use cases.
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
}
