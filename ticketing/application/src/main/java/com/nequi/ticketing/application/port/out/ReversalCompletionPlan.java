package com.nequi.ticketing.application.port.out;

import com.nequi.ticketing.domain.audit.AuditRecord;
import com.nequi.ticketing.domain.order.Order;
import java.util.Objects;

/**
 * AP-030 "Complete reversal" (2 items): the Order loses the mark and its reversal index entry and records
 * {@code paymentReversalCompletedAt}; audit {@code PAYMENT_REVERSAL_CONFIRMED}. Guard: reversal pending
 * and same {@code paymentAttemptId} (ADR-025).
 */
public record ReversalCompletionPlan(Order current, Order completed, AuditRecord audit) {

    public ReversalCompletionPlan {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(completed, "completed");
        Objects.requireNonNull(audit, "audit");
        if (!current.orderId().equals(completed.orderId()) || !current.reversalPending()
                || completed.reversalPending()) {
            throw new IllegalArgumentException("the completed Order must complete the pending reversal");
        }
    }
}
