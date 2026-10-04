package com.nequi.ticketing.application.port.out;

import java.util.Objects;

/** Projection of a Ticket in {@code AVAILABLE} read from the availability index (AP-020). */
public record AvailableTicket(String ticketId, String section, String row, int seat) {

    public AvailableTicket {
        Objects.requireNonNull(ticketId, "ticketId");
        Objects.requireNonNull(section, "section");
        Objects.requireNonNull(row, "row");
    }
}
