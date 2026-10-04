package com.nequi.ticketing.application.usecase;

import com.nequi.ticketing.application.port.out.CancellationOutcome.CancellationStatus;
import com.nequi.ticketing.domain.audit.AuditRecord;
import com.nequi.ticketing.domain.audit.AuditRecord.Actor;
import com.nequi.ticketing.domain.audit.AuditRecord.AuditCode;
import com.nequi.ticketing.domain.audit.AuditRecordBuilder;
import com.nequi.ticketing.domain.event.Event;
import com.nequi.ticketing.domain.order.Order;
import com.nequi.ticketing.domain.order.Order.OrderStatus;
import com.nequi.ticketing.domain.ticket.Ticket.TicketState;
import java.time.Instant;

/**
 * Audit records of the {@code worker} role transitions, built with the domain catalog of ADR-031. A
 * closing transition that marks the payment reversal records {@code PAYMENT_REVERSAL_REQUESTED} inside
 * its own record, and "Expire" after a late approval records {@code LATE_APPROVAL_NOT_APPLIED} inside it.
 */
final class WorkerAudits {

    private WorkerAudits() {
    }

    static AuditRecord paymentStarted(Order started, Actor actor, String correlationId, Instant occurredAt) {
        return orderBuilder(started, AuditCode.PAYMENT_STARTED, actor, correlationId, occurredAt)
                .transitions("ST-003")
                .orderStates(OrderStatus.CREATED, OrderStatus.CREATED)
                .ticketStates(TicketState.RESERVED, TicketState.PENDING_CONFIRMATION)
                .build();
    }

    static AuditRecord paymentApproved(Order confirmed, Actor actor, String correlationId, Instant occurredAt) {
        return orderBuilder(confirmed, AuditCode.PAYMENT_APPROVED, actor, correlationId, occurredAt)
                .transitions("ST-004", "ST-007")
                .orderStates(OrderStatus.CREATED, OrderStatus.CONFIRMED)
                .ticketStates(TicketState.PENDING_CONFIRMATION, TicketState.SOLD)
                .build();
    }

    static AuditRecord paymentDeclined(Order rejected, Actor actor, String correlationId, Instant occurredAt) {
        return closing(rejected, AuditCode.PAYMENT_DECLINED, actor, correlationId, occurredAt)
                .transitions("ST-005", "ST-008")
                .build();
    }

    static AuditRecord processingFailed(Order failed, Actor actor, String correlationId, Instant occurredAt) {
        return closing(failed, AuditCode.PROCESSING_FAILED, actor, correlationId, occurredAt)
                .transitions("ST-005", "ST-009")
                .build();
    }

    static AuditRecord reservationExpired(Order expired, boolean lateApproval, Actor actor, String correlationId,
            Instant occurredAt) {
        AuditRecordBuilder builder = closing(expired, AuditCode.RESERVATION_EXPIRED, actor, correlationId, occurredAt)
                .transitions("ST-002", "ST-010");
        if (lateApproval) {
            builder.include(AuditCode.LATE_APPROVAL_NOT_APPLIED);
        }
        return builder.build();
    }

    static AuditRecord lateApprovalNotApplied(Order current, boolean reversalMarked, Actor actor, String correlationId,
            Instant occurredAt) {
        AuditRecordBuilder builder = orderBuilder(current, AuditCode.LATE_APPROVAL_NOT_APPLIED, actor, correlationId,
                occurredAt)
                .orderStates(current.status(), current.status());
        if (reversalMarked) {
            builder.include(AuditCode.PAYMENT_REVERSAL_REQUESTED);
        }
        return builder.build();
    }

    static AuditRecord reversalConfirmed(Order completed, CancellationStatus status, Actor actor, String correlationId,
            Instant occurredAt) {
        return orderBuilder(completed, AuditCode.PAYMENT_REVERSAL_CONFIRMED, actor, correlationId, occurredAt)
                .orderStates(completed.status(), completed.status())
                .cause(status.name())
                .build();
    }

    static AuditRecord reversalExhausted(Order exhausted, Actor actor, String correlationId, Instant occurredAt) {
        return orderBuilder(exhausted, AuditCode.PAYMENT_REVERSAL_EXHAUSTED, actor, correlationId, occurredAt)
                .orderStates(exhausted.status(), exhausted.status())
                .build();
    }

    static AuditRecord eventEnabled(Event enabled, int complimentaryCount, Actor actor, String correlationId,
            Instant occurredAt) {
        return new AuditRecordBuilder()
                .code(AuditCode.EVENT_ENABLED)
                .transitions("ST-012")
                .event(enabled.eventId())
                .inventoryCounts(enabled.capacity(), enabled.capacity() - complimentaryCount, complimentaryCount)
                .actor(actor)
                .correlation(correlationId)
                .occurredAt(occurredAt)
                .build();
    }

    static AuditRecord eventProvisioningFailed(Event failed, Actor actor, String correlationId, Instant occurredAt) {
        return new AuditRecordBuilder()
                .code(AuditCode.EVENT_PROVISIONING_FAILED)
                .transitions("ST-013")
                .event(failed.eventId())
                .cause("PROVISIONING_FAILED")
                .actor(actor)
                .correlation(correlationId)
                .occurredAt(occurredAt)
                .build();
    }

    /** Terminal transition with release: Tickets leave their reserved state and return to AVAILABLE. */
    private static AuditRecordBuilder closing(Order closed, AuditCode code, Actor actor, String correlationId,
            Instant occurredAt) {
        AuditRecordBuilder builder = orderBuilder(closed, code, actor, correlationId, occurredAt)
                .orderStates(OrderStatus.CREATED, closed.status())
                .ticketStates(closed.paymentAttempt() == null ? TicketState.RESERVED : TicketState.PENDING_CONFIRMATION,
                        TicketState.AVAILABLE)
                .cause(closed.failureCause().name());
        if (closed.reversalPlan() != null) {
            builder.include(AuditCode.PAYMENT_REVERSAL_REQUESTED);
        }
        return builder;
    }

    private static AuditRecordBuilder orderBuilder(Order order, AuditCode code, Actor actor, String correlationId,
            Instant occurredAt) {
        return new AuditRecordBuilder()
                .code(code)
                .event(order.eventId())
                .order(order.orderId())
                .tickets(order.ticketIds())
                .actor(actor)
                .correlation(correlationId)
                .paymentAttempt(order.paymentAttempt() == null ? null : order.paymentAttempt().paymentAttemptId())
                .occurredAt(occurredAt);
    }
}
