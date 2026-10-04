package com.nequi.ticketing.application.port.out;

import com.nequi.ticketing.domain.audit.AuditRecord;
import com.nequi.ticketing.domain.order.Order;
import java.time.Instant;
import java.util.Objects;

/**
 * AP-015 "Fail (enqueue)" (ST-005 + ST-009): Order {@code CREATED -> FAILED} with cause
 * {@code PROCESSING_UNAVAILABLE}, its Tickets released, the audit record and the active Order lock
 * removed. Guards: Order {@code CREATED} without PaymentAttempt; Tickets reserved by this Order
 * (ADR-025, ADR-026).
 */
public record EnqueueFailurePlan(Order current, Order failed, AuditRecord audit, Instant failedAt) {

    public EnqueueFailurePlan {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(failed, "failed");
        Objects.requireNonNull(audit, "audit");
        Objects.requireNonNull(failedAt, "failedAt");
        if (!current.orderId().equals(failed.orderId()) || failed.status() != Order.OrderStatus.FAILED) {
            throw new IllegalArgumentException("the failed Order must be the enqueue failure of the current Order");
        }
    }
}
