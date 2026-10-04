package com.nequi.ticketing.application.port.out;

import com.nequi.ticketing.domain.audit.AuditRecord;
import com.nequi.ticketing.domain.order.Order;
import com.nequi.ticketing.domain.order.Order.OrderStatus;
import java.time.Instant;
import java.util.Objects;

/**
 * AP-015 "Close with release" executed by the {@code worker} role (at most 13 items): the Order reaches
 * its terminal state (with the payment reversal mark when the domain sets it), its N Tickets are
 * released, the audit record is inserted and the active Order lock is removed. Guards per kind
 * ({@code ticketing.data-model.v2.md} §5):
 * <ul>
 *   <li>{@link Kind#REJECT}: Order {@code CREATED}, without quarantine, same {@code paymentAttemptId};</li>
 *   <li>{@link Kind#FAIL_PROCESSING}: Order {@code CREATED}, without quarantine, PaymentAttempt identity
 *       equal to the one read (including its absence);</li>
 *   <li>{@link Kind#EXPIRE}: Order {@code CREATED}, without quarantine, {@code expiresAt <= now}.</li>
 * </ul>
 * Every Ticket {@code RESERVED} or {@code PENDING_CONFIRMATION} of this Order; lock absent or owned by
 * this Order (ADR-025). {@code providerReference} is set only when the provider returned one.
 */
public record ClosurePlan(Kind kind, Order current, Order closed, String providerReference, AuditRecord audit,
        Instant now) {

    public ClosurePlan {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(closed, "closed");
        Objects.requireNonNull(audit, "audit");
        Objects.requireNonNull(now, "now");
        if (!current.orderId().equals(closed.orderId()) || closed.status() != kind.target()) {
            throw new IllegalArgumentException("the closed Order must be the " + kind + " of the current Order");
        }
    }

    public enum Kind {
        REJECT(OrderStatus.REJECTED),
        FAIL_PROCESSING(OrderStatus.FAILED),
        EXPIRE(OrderStatus.EXPIRED);

        private final OrderStatus target;

        Kind(OrderStatus target) {
            this.target = target;
        }

        public OrderStatus target() {
            return target;
        }
    }
}
