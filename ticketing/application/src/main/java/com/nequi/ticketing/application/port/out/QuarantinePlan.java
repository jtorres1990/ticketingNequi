package com.nequi.ticketing.application.port.out;

import com.nequi.ticketing.domain.audit.AuditRecord;
import com.nequi.ticketing.domain.order.Order;
import java.util.Objects;

/**
 * AP-031: technical quarantine of an Order in {@code CREATED} with its audit record; no Ticket is
 * written and the active Order lock is kept (ADR-025, ADR-032). Guard: Order {@code CREATED} without
 * quarantine.
 */
public record QuarantinePlan(Order current, Order quarantined, AuditRecord audit) {

    public QuarantinePlan {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(quarantined, "quarantined");
        Objects.requireNonNull(audit, "audit");
        if (!current.orderId().equals(quarantined.orderId()) || quarantined.quarantinedAt() == null) {
            throw new IllegalArgumentException("the quarantined Order must be the quarantine of the current Order");
        }
    }
}
