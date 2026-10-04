package com.nequi.ticketing.application.port.out;

import java.util.Objects;

/** One failed condition of a cancelled atomic write; {@code ticketId} is set only for Ticket items. */
public record ItemFailure(FailedItem item, String ticketId) {

    public ItemFailure {
        Objects.requireNonNull(item, "item");
        boolean ticketItem = item == FailedItem.TICKET_MISSING || item == FailedItem.TICKET_STATE;
        if (ticketItem == (ticketId == null)) {
            throw new IllegalArgumentException("ticketId is required exactly for Ticket items");
        }
    }

    public static ItemFailure of(FailedItem item) {
        return new ItemFailure(item, null);
    }

    public static ItemFailure ticket(FailedItem item, String ticketId) {
        return new ItemFailure(item, Objects.requireNonNull(ticketId, "ticketId"));
    }
}
