package com.nequi.ticketing.application.port.in;

/** API-002 query; a {@code null} limit selects the configured default page size. */
public record ListEventsQuery(Integer limit, String cursor) {
}
