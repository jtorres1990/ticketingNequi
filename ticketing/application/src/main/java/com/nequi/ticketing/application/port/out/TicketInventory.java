package com.nequi.ticketing.application.port.out;

import com.nequi.ticketing.domain.event.Event;
import reactor.core.publisher.Mono;

/**
 * Outbound port: Ticket inventory reads used by the {@code api} role (ADR-034, ADR-040). Batch write,
 * verification and purge are added with the provisioning use cases.
 */
public interface TicketInventory {

    /** AP-005: true when the Event has at least one Ticket in {@code AVAILABLE} (probe by shards). */
    Mono<Boolean> hasAvailable(Event event);

    /** AP-021: number of Tickets in {@code AVAILABLE} (parallel count over the Event shards). */
    Mono<Long> countAvailable(Event event);

    /**
     * AP-020: one page of Tickets in {@code AVAILABLE}, optionally restricted to a section. An invalid
     * cursor, or one issued for another Event or filter, is signalled as
     * {@link com.nequi.ticketing.domain.error.ValidationException}.
     */
    Mono<AvailableTicketPage> findAvailablePage(Event event, String section, int pageSize, String cursor);
}
