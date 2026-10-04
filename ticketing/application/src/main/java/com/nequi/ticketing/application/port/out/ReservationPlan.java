package com.nequi.ticketing.application.port.out;

import com.nequi.ticketing.domain.audit.AuditRecord;
import com.nequi.ticketing.domain.order.ActiveOrderKey;
import com.nequi.ticketing.domain.order.Order;
import java.util.Objects;

/**
 * AP-008 (ST-001 + ST-006): N Tickets {@code AVAILABLE -> RESERVED} for the Order, the Order in
 * {@code CREATED} with its Reservation, the purchase idempotency record, the audit record and the
 * active Order lock, written atomically (ADR-023, ADR-025, ADR-027, ADR-032).
 */
public record ReservationPlan(
        Order order,
        IdempotencyRecord idempotency,
        AuditRecord audit,
        ActiveOrderKey activeOrderKey) {

    public ReservationPlan {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(idempotency, "idempotency");
        Objects.requireNonNull(audit, "audit");
        Objects.requireNonNull(activeOrderKey, "activeOrderKey");
        if (order.status() != Order.OrderStatus.CREATED
                || !idempotency.resourceId().equals(order.orderId())
                || !activeOrderKey.equals(ActiveOrderKey.of(order))) {
            throw new IllegalArgumentException("reservation items must describe the same new Order");
        }
    }
}
