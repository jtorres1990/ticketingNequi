package com.nequi.ticketing.application.port.out;

import com.nequi.ticketing.domain.audit.AuditRecord;
import com.nequi.ticketing.domain.order.Order;
import java.util.Objects;

/**
 * AP-030 "Exhaust reversal" (2 items): the Order moves to the {@code REVERSAL#EXHAUSTED} range with
 * {@code paymentReversalExhaustedAt}; audit {@code PAYMENT_REVERSAL_EXHAUSTED}. Guard: reversal pending
 * (ADR-025, ERR-017).
 */
public record ReversalExhaustionPlan(Order current, Order exhausted, AuditRecord audit) {

    public ReversalExhaustionPlan {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(exhausted, "exhausted");
        Objects.requireNonNull(audit, "audit");
        if (!current.orderId().equals(exhausted.orderId()) || !current.reversalPending()
                || exhausted.reversalPlan() == null || !exhausted.reversalPlan().exhausted()) {
            throw new IllegalArgumentException("the exhausted Order must exhaust the pending reversal");
        }
    }
}
