package com.nequi.ticketing.application.usecase;

import com.nequi.ticketing.domain.audit.AuditRecord;
import com.nequi.ticketing.domain.audit.AuditRecord.Actor;
import com.nequi.ticketing.domain.audit.AuditRecord.ActorType;
import com.nequi.ticketing.domain.audit.AuditRecord.AuditCode;
import com.nequi.ticketing.domain.audit.AuditRecordBuilder;
import com.nequi.ticketing.domain.event.Event;
import com.nequi.ticketing.domain.order.Order;
import com.nequi.ticketing.domain.order.Order.OrderStatus;
import com.nequi.ticketing.domain.ticket.Ticket.TicketState;
import java.time.Instant;

/** Audit records of the {@code api} role transitions, built with the domain catalog (ADR-031). */
final class ApiAudits {

    /** Technical actor for transitions decided by the {@code api} role itself. */
    static final Actor API_PROCESS = new Actor(ActorType.SYSTEM, "ticketing-api");

    private ApiAudits() {
    }

    static AuditRecord reservationCreated(Order order, String correlationId) {
        return new AuditRecordBuilder()
                .code(AuditCode.RESERVATION_CREATED)
                .transitions("ST-001", "ST-006")
                .event(order.eventId())
                .order(order.orderId())
                .orderStates(null, OrderStatus.CREATED)
                .ticketStates(TicketState.AVAILABLE, TicketState.RESERVED)
                .tickets(order.ticketIds())
                .actor(new Actor(ActorType.CUSTOMER, order.customerId()))
                .correlation(correlationId)
                .occurredAt(order.reservation().reservedAt())
                .build();
    }

    static AuditRecord enqueueFailed(Order failed, String correlationId, Instant occurredAt) {
        return new AuditRecordBuilder()
                .code(AuditCode.ENQUEUE_FAILED)
                .transitions("ST-005", "ST-009")
                .event(failed.eventId())
                .order(failed.orderId())
                .orderStates(OrderStatus.CREATED, OrderStatus.FAILED)
                .ticketStates(TicketState.RESERVED, TicketState.AVAILABLE)
                .tickets(failed.ticketIds())
                .cause(failed.failureCause().name())
                .actor(API_PROCESS)
                .correlation(correlationId)
                .occurredAt(occurredAt)
                .build();
    }

    static AuditRecord orderQuarantined(Order quarantined, String correlationId) {
        return new AuditRecordBuilder()
                .code(AuditCode.ORDER_QUARANTINED)
                .event(quarantined.eventId())
                .order(quarantined.orderId())
                .tickets(quarantined.ticketIds())
                .cause(quarantined.quarantineReason())
                .actor(API_PROCESS)
                .correlation(correlationId)
                .occurredAt(quarantined.quarantinedAt())
                .build();
    }

    static AuditRecord eventProvisioningRequested(Event event, String adminSubject, String correlationId,
            Instant occurredAt) {
        return new AuditRecordBuilder()
                .code(AuditCode.EVENT_PROVISIONING_REQUESTED)
                .transitions("ST-011")
                .event(event.eventId())
                .actor(new Actor(ActorType.ADMIN, adminSubject))
                .correlation(correlationId)
                .occurredAt(occurredAt)
                .build();
    }
}
