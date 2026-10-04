package com.nequi.ticketing.application.port.out;

import com.nequi.ticketing.domain.order.Order;
import java.time.Instant;
import java.util.Objects;

/**
 * AP-010: Order item read with strong consistency. {@code enqueuedAt} is the technical enqueue marker
 * (ADR-026) and {@code paymentLease} the lease of the active PaymentAttempt (ADR-027, ADR-029); neither
 * is ever exposed by API-005.
 */
public record OrderRecord(Order order, Instant createdAt, Instant updatedAt, Instant enqueuedAt,
        PaymentLease paymentLease) {

    public OrderRecord {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public OrderRecord(Order order, Instant createdAt, Instant updatedAt, Instant enqueuedAt) {
        this(order, createdAt, updatedAt, enqueuedAt, null);
    }

    public boolean enqueued() {
        return enqueuedAt != null;
    }
}
