package com.nequi.ticketing.application.port.in;

import com.nequi.ticketing.domain.order.Order.FunctionalCause;
import com.nequi.ticketing.domain.order.Order.OrderStatus;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Order as seen by its CUSTOMER (FR-009, OpenAPI v2 {@code Order}). Technical attributes (enqueue
 * marker, payment lease, payment reversal mark, quarantine) are deliberately absent (ADR-025).
 */
public record OrderView(
        String orderId,
        String eventId,
        List<String> ticketIds,
        OrderStatus status,
        FunctionalCause failureCause,
        Instant reservationExpiresAt,
        Instant createdAt,
        Instant updatedAt) {

    public OrderView {
        ticketIds = List.copyOf(Objects.requireNonNull(ticketIds, "ticketIds"));
    }
}
