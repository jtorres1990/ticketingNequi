package com.nequi.ticketing.application.port.out;

import com.nequi.ticketing.domain.order.Order;
import java.time.Instant;
import java.util.Objects;

/**
 * AP-010: Order item read with strong consistency. {@code enqueuedAt} is the technical enqueue marker
 * (ADR-026); it is never exposed by API-005.
 */
public record OrderRecord(Order order, Instant createdAt, Instant updatedAt, Instant enqueuedAt) {

    public OrderRecord {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public boolean enqueued() {
        return enqueuedAt != null;
    }
}
