package com.nequi.ticketing.application.port.in;

/**
 * API-003 query. {@code section} and {@code cursor} are optional; a {@code null} page size selects the
 * configured default.
 */
public record AvailabilityQuery(String eventId, String section, Integer pageSize, String cursor) {
}
