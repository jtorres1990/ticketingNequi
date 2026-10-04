package com.nequi.ticketing.application.port.in;

/** A Ticket in {@code AVAILABLE}: identifier, section, row and seat (FR-012). */
public record AvailableTicketView(String ticketId, String section, String row, int seat) {
}
