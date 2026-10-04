package com.nequi.ticketing.domain.ticket;

import static com.nequi.ticketing.domain.shared.DomainChecks.required;

import com.nequi.ticketing.domain.error.InvalidStateTransitionException;
import com.nequi.ticketing.domain.event.InventoryDefinition.TicketSeed;

public record Ticket(
        String eventId,
        String ticketId,
        String section,
        String row,
        int seat,
        TicketState state,
        String orderId) {

    public static Ticket provision(String eventId, TicketSeed seed) {
        required(seed, "ticketSeed");
        return new Ticket(required(eventId, "eventId"), seed.ticketId(), seed.section(), seed.row(), seed.seat(),
                seed.complimentary() ? TicketState.COMPLIMENTARY : TicketState.AVAILABLE, null);
    }

    public Ticket reserve(String newOrderId) {
        requireState(TicketState.AVAILABLE);
        return with(TicketState.RESERVED, required(newOrderId, "orderId"));
    }

    public Ticket startPayment(String expectedOrderId) {
        requireOwned(TicketState.RESERVED, expectedOrderId);
        return with(TicketState.PENDING_CONFIRMATION, orderId);
    }

    public Ticket sell(String expectedOrderId) {
        requireOwned(TicketState.PENDING_CONFIRMATION, expectedOrderId);
        return with(TicketState.SOLD, orderId);
    }

    public Ticket release(String expectedOrderId) {
        if (state != TicketState.RESERVED && state != TicketState.PENDING_CONFIRMATION) {
            throw new InvalidStateTransitionException("only a reserved ticket can be released");
        }
        requireOwner(expectedOrderId);
        return with(TicketState.AVAILABLE, null);
    }

    private void requireState(TicketState expected) {
        if (state != expected) {
            throw new InvalidStateTransitionException("ticket must be " + expected);
        }
    }

    private void requireOwned(TicketState expected, String expectedOrderId) {
        requireState(expected);
        requireOwner(expectedOrderId);
    }

    private void requireOwner(String expectedOrderId) {
        if (!required(expectedOrderId, "orderId").equals(orderId)) {
            throw new InvalidStateTransitionException("ticket belongs to another order");
        }
    }

    private Ticket with(TicketState newState, String newOrderId) {
        return new Ticket(eventId, ticketId, section, row, seat, newState, newOrderId);
    }

    public enum TicketState {
        AVAILABLE,
        RESERVED,
        PENDING_CONFIRMATION,
        SOLD,
        COMPLIMENTARY;

        public boolean terminal() {
            return this == SOLD || this == COMPLIMENTARY;
        }
    }
}
