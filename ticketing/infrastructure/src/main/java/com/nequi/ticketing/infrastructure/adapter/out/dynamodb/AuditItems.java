package com.nequi.ticketing.infrastructure.adapter.out.dynamodb;

import static com.nequi.ticketing.infrastructure.adapter.out.dynamodb.Attributes.instant;
import static com.nequi.ticketing.infrastructure.adapter.out.dynamodb.Attributes.n;
import static com.nequi.ticketing.infrastructure.adapter.out.dynamodb.Attributes.s;
import static com.nequi.ticketing.infrastructure.adapter.out.dynamodb.Attributes.strings;

import com.nequi.ticketing.domain.audit.AuditRecord;
import com.nequi.ticketing.domain.audit.AuditRecord.Actor;
import com.nequi.ticketing.domain.audit.AuditRecord.ActorType;
import com.nequi.ticketing.domain.audit.AuditRecord.AuditCode;
import com.nequi.ticketing.domain.order.Order.OrderStatus;
import com.nequi.ticketing.domain.ticket.Ticket.TicketState;
import java.util.HashMap;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Insert-only audit items of {@code ticketing.data-model.v2.md} §3 (ADR-031), plus the list attribute
 * {@code includedCauses} approved in IV-013 (catalog codes recorded inside the same transition record,
 * omitted when empty). The audit code is part of the sort key {@code AUDIT#<occurredAt>#<code>#<suffix>}.
 * Order records carry the attributes of the Order audit; Event records carry the attributes of the Event
 * audit (no transition identifiers, Order or Ticket states in the data model).
 */
final class AuditItems {

    static final String ORDER_ENTITY = "ORDER_AUDIT";
    static final String EVENT_ENTITY = "EVENT_AUDIT";
    static final String ENTITY_TYPE = "entityType";
    static final String TRANSITION_IDS = "transitionIds";
    static final String ORDER_FROM = "orderFrom";
    static final String ORDER_TO = "orderTo";
    static final String TICKET_FROM = "ticketFrom";
    static final String TICKET_TO = "ticketTo";
    static final String TICKET_IDS = "ticketIds";
    static final String EVENT_ID = "eventId";
    static final String CAUSE = "cause";
    static final String CAPACITY = "capacity";
    static final String AVAILABLE_COUNT = "availableCount";
    static final String COMPLIMENTARY_COUNT = "complimentaryCount";
    static final String ACTOR_TYPE = "actorType";
    static final String ACTOR_ID = "actorId";
    static final String PAYMENT_ATTEMPT_ID = "paymentAttemptId";
    static final String CORRELATION_ID = "correlationId";
    static final String OCCURRED_AT = "occurredAt";
    static final String INCLUDED_CAUSES = "includedCauses";

    private AuditItems() {
    }

    static Map<String, AttributeValue> item(AuditRecord audit) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(Keys.PK, s(Keys.auditPartition(audit)));
        item.put(Keys.SK, s(Keys.auditSort(audit)));
        boolean orderRecord = audit.orderId() != null;
        item.put(ENTITY_TYPE, s(orderRecord ? ORDER_ENTITY : EVENT_ENTITY));
        if (orderRecord) {
            item.put(TRANSITION_IDS, strings(audit.transitionIds()));
            putIfPresent(item, ORDER_FROM, audit.orderFrom() == null ? null : audit.orderFrom().name());
            putIfPresent(item, ORDER_TO, audit.orderTo() == null ? null : audit.orderTo().name());
            putIfPresent(item, TICKET_FROM, audit.ticketFrom() == null ? null : audit.ticketFrom().name());
            putIfPresent(item, TICKET_TO, audit.ticketTo() == null ? null : audit.ticketTo().name());
            item.put(TICKET_IDS, strings(audit.ticketIds()));
            putIfPresent(item, PAYMENT_ATTEMPT_ID, audit.paymentAttemptId());
        } else {
            putIfPresent(item, CAPACITY, audit.capacity());
            putIfPresent(item, AVAILABLE_COUNT, audit.availableCount());
            putIfPresent(item, COMPLIMENTARY_COUNT, audit.complimentaryCount());
        }
        putIfPresent(item, EVENT_ID, audit.eventId());
        putIfPresent(item, CAUSE, audit.cause());
        item.put(ACTOR_TYPE, s(audit.actor().type().name()));
        item.put(ACTOR_ID, s(audit.actor().id()));
        item.put(CORRELATION_ID, s(audit.correlationId()));
        item.put(OCCURRED_AT, instant(audit.occurredAt()));
        if (!audit.includedCodes().isEmpty()) {
            item.put(INCLUDED_CAUSES, strings(audit.includedCodes().stream().map(AuditCode::name).toList()));
        }
        return item;
    }

    /** AP-017: the record as persisted (Event records come back without transition identifiers). */
    static AuditRecord record(Map<String, AttributeValue> item) {
        String partition = Attributes.string(item, Keys.PK);
        String[] sortKey = Attributes.string(item, Keys.SK).split("#", -1);
        String orderFrom = Attributes.optionalString(item, ORDER_FROM);
        String orderTo = Attributes.optionalString(item, ORDER_TO);
        String ticketFrom = Attributes.optionalString(item, TICKET_FROM);
        String ticketTo = Attributes.optionalString(item, TICKET_TO);
        return new AuditRecord(
                AuditCode.valueOf(sortKey[2]),
                Attributes.stringList(item, TRANSITION_IDS),
                Attributes.optionalString(item, EVENT_ID),
                partition.startsWith("ORDER#") ? partition.substring("ORDER#".length()) : null,
                orderFrom == null ? null : OrderStatus.valueOf(orderFrom),
                orderTo == null ? null : OrderStatus.valueOf(orderTo),
                ticketFrom == null ? null : TicketState.valueOf(ticketFrom),
                ticketTo == null ? null : TicketState.valueOf(ticketTo),
                Attributes.stringList(item, TICKET_IDS),
                Attributes.optionalString(item, CAUSE),
                new Actor(ActorType.valueOf(Attributes.string(item, ACTOR_TYPE)), Attributes.string(item, ACTOR_ID)),
                Attributes.string(item, CORRELATION_ID),
                Attributes.optionalString(item, PAYMENT_ATTEMPT_ID),
                Attributes.instant(item, OCCURRED_AT),
                Attributes.stringList(item, INCLUDED_CAUSES).stream().map(AuditCode::valueOf).toList(),
                Attributes.optionalInteger(item, CAPACITY),
                Attributes.optionalInteger(item, AVAILABLE_COUNT),
                Attributes.optionalInteger(item, COMPLIMENTARY_COUNT));
    }

    private static void putIfPresent(Map<String, AttributeValue> item, String name, String value) {
        if (value != null) {
            item.put(name, s(value));
        }
    }

    private static void putIfPresent(Map<String, AttributeValue> item, String name, Integer value) {
        if (value != null) {
            item.put(name, n(value));
        }
    }
}
