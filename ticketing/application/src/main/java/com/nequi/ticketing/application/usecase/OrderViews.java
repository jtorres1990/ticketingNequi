package com.nequi.ticketing.application.usecase;

import com.nequi.ticketing.application.port.in.OrderView;
import com.nequi.ticketing.application.port.out.OrderRecord;
import com.nequi.ticketing.domain.order.Order;
import java.time.Instant;

/**
 * Maps the Order item to the customer view. Only business attributes are copied: quarantine, payment
 * reversal mark, lease and enqueue marker never reach API-005 (ADR-025, ADR-027).
 */
final class OrderViews {

    private OrderViews() {
    }

    static OrderView of(OrderRecord record) {
        return of(record.order(), record.createdAt(), record.updatedAt());
    }

    static OrderView of(Order order, Instant createdAt, Instant updatedAt) {
        return new OrderView(
                order.orderId(),
                order.eventId(),
                order.ticketIds(),
                order.status(),
                order.failureCause(),
                order.reservation().expiresAt(),
                createdAt,
                updatedAt);
    }
}
