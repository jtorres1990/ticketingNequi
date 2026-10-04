package com.nequi.ticketing.application.port.out;

import com.nequi.ticketing.domain.event.Event;
import com.nequi.ticketing.domain.ticket.Ticket;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Outbound port: Ticket inventory (ADR-034, ADR-040): batch write, verification and purge used by the
 * provisioning use cases, and the availability reads used by the {@code api} role.
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

    /**
     * AP-002: writes one provisioning batch of Tickets of {@code event} in their initial state
     * ({@code AVAILABLE} indexed for availability, or {@code COMPLIMENTARY}). Idempotent per key; called
     * only after a successful AP-024 check (ADR-024).
     */
    Mono<Void> writeBatch(Event event, List<Ticket> tickets);

    /**
     * AP-025: strongly consistent batch read of every expected key, reporting the Tickets that are
     * missing or not in the initial state of {@code expected}.
     */
    Mono<InventoryVerification> verify(Event event, List<Ticket> expected);

    /** AP-027: deletes the Tickets of a {@code FAILED} Event by their generated keys (batch delete). */
    Mono<Void> purge(Event event, List<String> ticketIds);
}
