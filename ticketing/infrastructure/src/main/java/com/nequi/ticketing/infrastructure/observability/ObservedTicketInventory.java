package com.nequi.ticketing.infrastructure.observability;

import com.nequi.ticketing.application.port.out.AvailableTicketPage;
import com.nequi.ticketing.application.port.out.InventoryVerification;
import com.nequi.ticketing.application.port.out.TicketInventory;
import com.nequi.ticketing.domain.event.Event;
import com.nequi.ticketing.domain.ticket.Ticket;
import java.util.List;
import java.util.Objects;
import reactor.core.publisher.Mono;

/**
 * Counts the available-count queries that reach the store: together with {@code ticketing.availability.requests}
 * it gives the hit ratio of the shared 1 s count cache of CMP-004 (ADR-040; aws-target §7 "aciertos del caché
 * del recuento de disponibles").
 */
final class ObservedTicketInventory implements TicketInventory {

    private final TicketInventory delegate;
    private final Telemetry telemetry;

    ObservedTicketInventory(TicketInventory delegate, Telemetry telemetry) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.telemetry = telemetry;
    }

    @Override
    public Mono<Boolean> hasAvailable(Event event) {
        return delegate.hasAvailable(event);
    }

    @Override
    public Mono<Long> countAvailable(Event event) {
        return Mono.defer(() -> {
            telemetry.counter(MetricNames.AVAILABILITY_COUNT_QUERIES).increment();
            return delegate.countAvailable(event);
        });
    }

    @Override
    public Mono<AvailableTicketPage> findAvailablePage(Event event, String section, int pageSize, String cursor) {
        return delegate.findAvailablePage(event, section, pageSize, cursor);
    }

    @Override
    public Mono<Void> writeBatch(Event event, List<Ticket> tickets) {
        return delegate.writeBatch(event, tickets);
    }

    @Override
    public Mono<InventoryVerification> verify(Event event, List<Ticket> expected) {
        return delegate.verify(event, expected);
    }

    @Override
    public Mono<Void> purge(Event event, List<String> ticketIds) {
        return delegate.purge(event, ticketIds);
    }
}
