package com.nequi.ticketing.domain.audit;

import com.nequi.ticketing.domain.audit.AuditRecord.Actor;
import com.nequi.ticketing.domain.audit.AuditRecord.AuditCode;
import com.nequi.ticketing.domain.order.Order.OrderStatus;
import com.nequi.ticketing.domain.ticket.Ticket.TicketState;
import java.time.Instant;
import java.util.List;

public final class AuditRecordBuilder {

    private AuditCode code;
    private List<String> transitionIds = List.of();
    private String eventId;
    private String orderId;
    private OrderStatus orderFrom;
    private OrderStatus orderTo;
    private TicketState ticketFrom;
    private TicketState ticketTo;
    private List<String> ticketIds = List.of();
    private String cause;
    private Actor actor;
    private String correlationId;
    private String paymentAttemptId;
    private Instant occurredAt;

    public AuditRecordBuilder code(AuditCode value) { this.code = value; return this; }
    public AuditRecordBuilder transitions(String... values) { this.transitionIds = List.of(values); return this; }
    public AuditRecordBuilder event(String value) { this.eventId = value; return this; }
    public AuditRecordBuilder order(String value) { this.orderId = value; return this; }
    public AuditRecordBuilder orderStates(OrderStatus from, OrderStatus to) { this.orderFrom = from; this.orderTo = to; return this; }
    public AuditRecordBuilder ticketStates(TicketState from, TicketState to) { this.ticketFrom = from; this.ticketTo = to; return this; }
    public AuditRecordBuilder tickets(List<String> values) { this.ticketIds = List.copyOf(values); return this; }
    public AuditRecordBuilder cause(String value) { this.cause = value; return this; }
    public AuditRecordBuilder actor(Actor value) { this.actor = value; return this; }
    public AuditRecordBuilder correlation(String value) { this.correlationId = value; return this; }
    public AuditRecordBuilder paymentAttempt(String value) { this.paymentAttemptId = value; return this; }
    public AuditRecordBuilder occurredAt(Instant value) { this.occurredAt = value; return this; }

    public AuditRecord build() {
        return new AuditRecord(code, transitionIds, eventId, orderId, orderFrom, orderTo, ticketFrom,
                ticketTo, ticketIds, cause, actor, correlationId, paymentAttemptId, occurredAt);
    }
}
