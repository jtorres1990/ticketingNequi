package com.nequi.ticketing.application.usecase;

import com.nequi.ticketing.application.error.RequestRejectedException;
import com.nequi.ticketing.application.port.in.AvailabilityQuery;
import com.nequi.ticketing.application.port.in.AvailabilityView;
import com.nequi.ticketing.application.port.in.AvailableTicketView;
import com.nequi.ticketing.application.port.in.EventSummaryPage;
import com.nequi.ticketing.application.port.in.EventSummaryView;
import com.nequi.ticketing.application.port.in.GetEventAvailabilityUseCase;
import com.nequi.ticketing.application.port.in.ListEventsQuery;
import com.nequi.ticketing.application.port.in.ListEventsUseCase;
import com.nequi.ticketing.application.port.out.AvailableTicketPage;
import com.nequi.ticketing.application.port.out.Clock;
import com.nequi.ticketing.application.port.out.EventCatalog;
import com.nequi.ticketing.application.port.out.TicketInventory;
import com.nequi.ticketing.domain.error.ValidationException;
import com.nequi.ticketing.domain.event.Event;
import com.nequi.ticketing.domain.shared.DomainChecks;
import java.time.Instant;
import java.util.Objects;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * CMP-004 Event catalog and availability (FR-002, FR-012, BR-012, BR-017, BR-018, BR-022, BR-025,
 * BR-027, VAL-015, ADR-040). Only reads: the listing marks sold-out Events with a probe (AP-004,
 * AP-005) and the availability answers a cached count (AP-021) plus one page of tickets in
 * {@code AVAILABLE} (AP-020). Past Events answer availability informatively; Events not in
 * {@code ENABLED} do not exist for either operation.
 */
public final class EventCatalogService implements ListEventsUseCase, GetEventAvailabilityUseCase {

    private final EventCatalog eventCatalog;
    private final TicketInventory ticketInventory;
    private final Clock clock;
    private final ApiUseCaseSettings settings;
    private final AvailableCountCache countCache;

    public EventCatalogService(
            EventCatalog eventCatalog,
            TicketInventory ticketInventory,
            Clock clock,
            ApiUseCaseSettings settings) {
        this.eventCatalog = Objects.requireNonNull(eventCatalog, "eventCatalog");
        this.ticketInventory = Objects.requireNonNull(ticketInventory, "ticketInventory");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.countCache = new AvailableCountCache(clock, settings.availableCountCacheTtl());
    }

    @Override
    public Mono<EventSummaryPage> listEvents(ListEventsQuery query) {
        return Mono.defer(() -> {
            Objects.requireNonNull(query, "query");
            int limit = pageSize(query.limit(), settings.defaultEventPageSize(), settings.maximumEventPageSize(), "limit");
            Instant now = clock.now();
            return eventCatalog.listEnabledUpcoming(now, limit, query.cursor())
                    .flatMap(page -> Flux.fromIterable(page.events())
                            .filter(event -> event.visibleAt(now))
                            .flatMapSequential(this::summary, settings.soldOutProbeConcurrency())
                            .collectList()
                            .map(items -> new EventSummaryPage(items, page.nextCursor())));
        });
    }

    @Override
    public Mono<AvailabilityView> getAvailability(AvailabilityQuery query) {
        return Mono.defer(() -> {
            Objects.requireNonNull(query, "query");
            String eventId = DomainChecks.required(query.eventId(), "eventId");
            int pageSize = pageSize(query.pageSize(), settings.defaultAvailabilityPageSize(),
                    settings.maximumAvailabilityPageSize(), "pageSize");
            return eventCatalog.findEvent(eventId)
                    .filter(Event::availabilityIsVisible)
                    .switchIfEmpty(Mono.error(RequestRejectedException::eventNotFound))
                    .flatMap(event -> availability(event, query, pageSize));
        });
    }

    private Mono<EventSummaryView> summary(Event event) {
        return ticketInventory.hasAvailable(event)
                .defaultIfEmpty(Boolean.FALSE)
                .map(hasAvailable -> new EventSummaryView(
                        event.eventId(), event.name(), event.venue(), event.startsAt(), event.capacity(), !hasAvailable));
    }

    private Mono<AvailabilityView> availability(Event event, AvailabilityQuery query, int pageSize) {
        String section = query.section();
        if (section != null && !event.inventoryDefinition().definesSection(section)) {
            return Mono.error(new ValidationException("section is not defined in the Event inventory"));
        }
        Mono<AvailableCountCache.Count> count = countCache.get(event.eventId(), () -> ticketInventory.countAvailable(event));
        Mono<AvailableTicketPage> page = ticketInventory.findAvailablePage(event, section, pageSize, query.cursor());
        return Mono.zip(count, page).map(result -> new AvailabilityView(
                event.eventId(),
                event.name(),
                event.venue(),
                event.startsAt(),
                event.capacity(),
                event.inventoryDefinition().sectionCodes(),
                result.getT1().availableCount(),
                result.getT1().generatedAt(),
                true,
                section,
                result.getT2().tickets().stream()
                        .map(ticket -> new AvailableTicketView(ticket.ticketId(), ticket.section(), ticket.row(), ticket.seat()))
                        .toList(),
                result.getT2().nextCursor()));
    }

    private static int pageSize(Integer requested, int defaultSize, int maximumSize, String field) {
        if (requested == null) {
            return defaultSize;
        }
        if (requested < 1 || requested > maximumSize) {
            throw new ValidationException(field + " must be between 1 and " + maximumSize);
        }
        return requested;
    }
}
