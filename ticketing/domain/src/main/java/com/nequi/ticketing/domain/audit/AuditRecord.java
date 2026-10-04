package com.nequi.ticketing.domain.audit;

import static com.nequi.ticketing.domain.shared.DomainChecks.required;

import com.nequi.ticketing.domain.order.Order.OrderStatus;
import com.nequi.ticketing.domain.ticket.Ticket.TicketState;
import java.time.Instant;
import java.util.List;

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
        Instant occurredAt) {

    public AuditRecord {
        code = required(code, "auditCode");
        transitionIds = List.copyOf(required(transitionIds, "transitionIds"));
        ticketIds = List.copyOf(required(ticketIds, "ticketIds"));
        actor = required(actor, "actor");
        correlationId = required(correlationId, "correlationId");
        occurredAt = required(occurredAt, "occurredAt");
        if (eventId == null && orderId == null) {
            throw new IllegalArgumentException("audit must identify an Event or Order");
        }
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
