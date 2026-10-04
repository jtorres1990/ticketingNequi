package com.nequi.ticketing.application.port.out;

import com.nequi.ticketing.domain.audit.AuditRecord;
import com.nequi.ticketing.domain.order.Order;
import com.nequi.ticketing.domain.order.Order.OrderStatus;
import java.util.Objects;

/**
 * AP-032 "Late approval" (2 items): audit {@code LATE_APPROVAL_NOT_APPLIED} and, when the Order has no
 * reversal pending nor completed, the reversal mark. Guard: Order in {@code EXPIRED}, {@code FAILED} or
 * {@code REJECTED} with the same {@code paymentAttemptId} (ADR-008, ADR-025).
 */
public record LateApprovalPlan(Order current, Order updated, AuditRecord audit) {

    public LateApprovalPlan {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(updated, "updated");
        Objects.requireNonNull(audit, "audit");
        if (!current.orderId().equals(updated.orderId()) || current.paymentAttempt() == null
                || current.status() == OrderStatus.CREATED || current.status() == OrderStatus.CONFIRMED
                || updated.status() != current.status()) {
            throw new IllegalArgumentException("a late approval applies to an Order closed without confirmation");
        }
    }
}
