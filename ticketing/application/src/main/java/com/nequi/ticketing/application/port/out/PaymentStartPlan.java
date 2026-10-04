package com.nequi.ticketing.application.port.out;

import com.nequi.ticketing.domain.audit.AuditRecord;
import com.nequi.ticketing.domain.order.Order;
import java.time.Instant;
import java.util.Objects;

/**
 * AP-012 "Start payment" (ST-003, at most 12 items): the Order registers its PaymentAttempt and lease,
 * its N Tickets go {@code RESERVED -> PENDING_CONFIRMATION} and the audit record is inserted. Guards:
 * Order {@code CREATED}, without PaymentAttempt, without quarantine and {@code expiresAt > cutoffInstant}
 * ({@code now} plus the 15 s payment cutoff); every Ticket {@code RESERVED} by this Order (ADR-025,
 * ADR-008).
 */
public record PaymentStartPlan(Order current, Order started, PaymentLease lease, AuditRecord audit,
        Instant now, Instant cutoffInstant) {

    public PaymentStartPlan {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(started, "started");
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(audit, "audit");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(cutoffInstant, "cutoffInstant");
        if (!current.orderId().equals(started.orderId()) || current.paymentAttempt() != null
                || started.paymentAttempt() == null) {
            throw new IllegalArgumentException("the started Order must open the PaymentAttempt of the current Order");
        }
    }
}
