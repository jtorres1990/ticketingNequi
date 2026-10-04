package com.nequi.ticketing.domain.audit;

import static com.nequi.ticketing.domain.shared.DomainChecks.required;

import com.nequi.ticketing.domain.order.Order.OrderStatus;
import com.nequi.ticketing.domain.ticket.Ticket.TicketState;
import java.time.Instant;
import java.util.List;

/**
 * Insert-only audit record of one business transition (ADR-031). {@code includedCodes} lists the
 * catalog causes recorded inside the same transition record (for example
 * {@code PAYMENT_REVERSAL_REQUESTED} in the closing transition that marks the reversal, or
 * {@code LATE_APPROVAL_NOT_APPLIED} inside "Expire" after a late approval). {@code capacity},
 * {@code availableCount} and {@code complimentaryCount} are set only on Event records.
 */
public record AuditRecord(
        AuditCode code,
        List<String> transitionIds,
        String eventId,
        String orderId,
        OrderStatus orderFrom,
        OrderStatus orderTo,
        TicketState ticketFrom,
        TicketState ticketTo,
        List<String> ticketIds,
        String cause,
        Actor actor,
        String correlationId,
        String paymentAttemptId,
        Instant occurredAt,
        List<AuditCode> includedCodes,
        Integer capacity,
        Integer availableCount,
        Integer complimentaryCount) {

    public AuditRecord {
        code = required(code, "auditCode");
        transitionIds = List.copyOf(required(transitionIds, "transitionIds"));
        ticketIds = List.copyOf(required(ticketIds, "ticketIds"));
        actor = required(actor, "actor");
        correlationId = required(correlationId, "correlationId");
        occurredAt = required(occurredAt, "occurredAt");
        includedCodes = includedCodes == null ? List.of() : List.copyOf(includedCodes);
        if (eventId == null && orderId == null) {
            throw new IllegalArgumentException("audit must identify an Event or Order");
        }
        if (includedCodes.contains(code)) {
            throw new IllegalArgumentException("an included cause must differ from the record cause");
        }
    }

    public AuditRecord(
            AuditCode code,
            List<String> transitionIds,
            String eventId,
            String orderId,
            OrderStatus orderFrom,
            OrderStatus orderTo,
            TicketState ticketFrom,
            TicketState ticketTo,
            List<String> ticketIds,
            String cause,
            Actor actor,
            String correlationId,
            String paymentAttemptId,
            Instant occurredAt) {
        this(code, transitionIds, eventId, orderId, orderFrom, orderTo, ticketFrom, ticketTo, ticketIds, cause,
                actor, correlationId, paymentAttemptId, occurredAt, List.of(), null, null, null);
    }

    /** True when the record documents {@code candidate}, as its own cause or as an included cause. */
    public boolean records(AuditCode candidate) {
        return code == candidate || includedCodes.contains(candidate);
    }

    public record Actor(ActorType type, String id) {
        public Actor {
            type = required(type, "actorType");
            id = required(id, "actorId");
        }
    }

    public enum ActorType {
        ADMIN,
        CUSTOMER,
        SYSTEM,
        WORKER
    }

    public enum AuditCode {
        RESERVATION_CREATED,
        PAYMENT_STARTED,
        PAYMENT_APPROVED,
        PAYMENT_DECLINED,
        ENQUEUE_FAILED,
        PROCESSING_FAILED,
        RESERVATION_EXPIRED,
        PAYMENT_REVERSAL_REQUESTED,
        PAYMENT_REVERSAL_CONFIRMED,
        PAYMENT_REVERSAL_EXHAUSTED,
        LATE_APPROVAL_NOT_APPLIED,
        ORDER_QUARANTINED,
        EVENT_PROVISIONING_REQUESTED,
        EVENT_ENABLED,
        EVENT_PROVISIONING_FAILED
    }
}
