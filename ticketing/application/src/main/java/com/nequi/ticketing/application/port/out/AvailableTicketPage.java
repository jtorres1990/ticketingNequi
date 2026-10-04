package com.nequi.ticketing.application.port.out;

import java.util.List;
import java.util.Objects;

/** AP-020 page of tickets in {@code AVAILABLE}; the cursor is opaque and adapter-validated. */
public record AvailableTicketPage(List<AvailableTicket> tickets, String nextCursor) {

    public AvailableTicketPage {
        tickets = List.copyOf(Objects.requireNonNull(tickets, "tickets"));
    }
}
