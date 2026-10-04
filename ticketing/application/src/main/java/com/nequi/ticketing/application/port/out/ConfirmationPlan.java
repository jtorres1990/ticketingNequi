package com.nequi.ticketing.application.port.out;

import com.nequi.ticketing.domain.audit.AuditRecord;
import com.nequi.ticketing.domain.order.Order;
import java.time.Instant;
import java.util.Objects;

/**
 * AP-014 "Confirm" (ST-004 + ST-007, at most 13 items): Order {@code CONFIRMED} leaving the active Order
 * indexes, its N Tickets {@code SOLD}, the audit record and the active Order lock removed. Guards: Order
 * {@code CREATED}, without quarantine, same {@code paymentAttemptId}, {@code expiresAt > now}; every
 * Ticket {@code PENDING_CONFIRMATION} of this Order; lock absent or owned by this Order (ADR-025,
 * ADR-008). {@code providerReference} is the provider reference of the approval.
 */
public record ConfirmationPlan(Order current, Order confirmed, String providerReference, AuditRecord audit,
        Instant now) {

    public ConfirmationPlan {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(confirmed, "confirmed");
        Objects.requireNonNull(audit, "audit");
        Objects.requireNonNull(now, "now");
        if (!current.orderId().equals(confirmed.orderId()) || current.paymentAttempt() == null
                || confirmed.status() != Order.OrderStatus.CONFIRMED) {
            throw new IllegalArgumentException("the confirmed Order must be the confirmation of the current Order");
        }
    }
}
