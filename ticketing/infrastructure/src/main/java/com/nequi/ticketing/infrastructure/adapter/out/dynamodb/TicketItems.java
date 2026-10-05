package com.nequi.ticketing.infrastructure.adapter.out.dynamodb;

import static com.nequi.ticketing.infrastructure.adapter.out.dynamodb.Attributes.instant;
import static com.nequi.ticketing.infrastructure.adapter.out.dynamodb.Attributes.n;
import static com.nequi.ticketing.infrastructure.adapter.out.dynamodb.Attributes.s;

import com.nequi.ticketing.domain.ticket.Ticket;
import com.nequi.ticketing.domain.ticket.Ticket.TicketState;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Ticket item of {@code ticketing.data-model.v2.md} §3 ({@code TICKET#<eventId>#<ticketId>} / {@code #META}).
 * {@code state} holds exactly one of DS-001..DS-005 (BR-001, VAL-005); the item is in {@code GSI2} only
 * while {@code AVAILABLE}, under the shard of its {@code ticketId} (ADR-022, ADR-040).
 */
final class TicketItems {

    static final String ENTITY = "TICKET";
    static final String ENTITY_TYPE = "entityType";
    static final String EVENT_ID = "eventId";
    static final String TICKET_ID = "ticketId";
    static final String SECTION = "section";
    static final String ROW = "row";
    static final String SEAT = "seat";
    static final String STATE = "state";
    static final String ORDER_ID = "orderId";
    static final String UPDATED_AT = "updatedAt";
    static final String GSI2PK = "GSI2PK";
    static final String GSI2SK = "GSI2SK";

    private TicketItems() {
    }

    static Map<String, AttributeValue> key(String eventId, String ticketId) {
        return Keys.meta(Keys.ticket(eventId, ticketId));
    }

    /** AP-002: Ticket in its initial state ({@code AVAILABLE} indexed for availability, or {@code COMPLIMENTARY}). */
    static Map<String, AttributeValue> provisioned(Ticket ticket, int availabilityShards, Instant now) {
        Map<String, AttributeValue> item = new HashMap<>(key(ticket.eventId(), ticket.ticketId()));
        item.put(ENTITY_TYPE, s(ENTITY));
        item.put(EVENT_ID, s(ticket.eventId()));
        item.put(TICKET_ID, s(ticket.ticketId()));
        item.put(SECTION, s(ticket.section()));
        item.put(ROW, s(ticket.row()));
        item.put(SEAT, n(ticket.seat()));
        item.put(STATE, s(ticket.state().name()));
        item.put(UPDATED_AT, instant(now));
        if (ticket.state() == TicketState.AVAILABLE) {
            item.put(GSI2PK, s(availabilityPartition(ticket.eventId(), ticket.ticketId(), availabilityShards)));
            item.put(GSI2SK, s(Keys.availabilitySort(ticket.section(), ticket.row(), ticket.seat())));
        }
        return item;
    }

    static String availabilityPartition(String eventId, String ticketId, int availabilityShards) {
        return Keys.availability(eventId, Keys.availabilityShard(ticketId, availabilityShards));
    }

    /**
     * {@code GSI2SK} of a released Ticket, rebuilt from its server-generated identifier
     * {@code <section>-<row>-<seat>} (BR-033; section and row are alphanumeric).
     */
    static String availabilitySort(String ticketId) {
        String[] parts = ticketId.split("-", -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException("ticketId must be <section>-<row>-<seat>");
        }
        return Keys.availabilitySort(parts[0], parts[1], Integer.parseInt(parts[2]));
    }
}
