package com.nequi.ticketing.application.usecase;

import com.nequi.ticketing.application.port.in.CreateEventCommand;
import com.nequi.ticketing.application.port.in.PurchaseResult;
import com.nequi.ticketing.application.port.in.StartPurchaseCommand;
import com.nequi.ticketing.application.testdouble.InMemoryTicketingStore;
import com.nequi.ticketing.application.testdouble.MutableClock;
import com.nequi.ticketing.application.testdouble.RecordingOrderQueuePublisher;
import com.nequi.ticketing.application.testdouble.RecordingProvisioningQueuePublisher;
import com.nequi.ticketing.application.testdouble.SequentialIdGenerator;
import com.nequi.ticketing.domain.event.Event;
import com.nequi.ticketing.domain.event.InventoryDefinition;
import com.nequi.ticketing.domain.event.InventoryDefinition.ComplimentaryRange;
import com.nequi.ticketing.domain.event.InventoryDefinition.Row;
import com.nequi.ticketing.domain.event.InventoryDefinition.Section;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import reactor.core.publisher.Mono;

/** Wires the {@code api} use cases over the in-memory doubles with a controlled clock. */
final class ApiFixture {

    static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");
    static final String EVENT_ID = "e0000000-0000-4000-8000-000000000001";
    static final String CUSTOMER_A = "customer-a";
    static final String CUSTOMER_B = "customer-b";
    static final String ADMIN = "admin";
    static final String CORRELATION = "trace-0001";

    /** A: rows 1 and 2 with 10 seats each; B: row 1 with 5 seats, seat 5 complimentary. Capacity 25. */
    static final InventoryDefinition DEFINITION = new InventoryDefinition(
            List.of(
                    new Section("A", List.of(new Row("1", 10), new Row("2", 10))),
                    new Section("B", List.of(new Row("1", 5)))),
            List.of(new ComplimentaryRange("B", "1", 5, 5)));

    final MutableClock clock = new MutableClock(NOW);
    final SequentialIdGenerator ids = new SequentialIdGenerator();
    final InMemoryTicketingStore store = new InMemoryTicketingStore();
    final RecordingOrderQueuePublisher orderPublisher = new RecordingOrderQueuePublisher();
    final RecordingProvisioningQueuePublisher provisioningPublisher = new RecordingProvisioningQueuePublisher();
    final ApiUseCaseSettings settings = ApiUseCaseSettings.DEPLOYED;

    final PurchaseService purchases = new PurchaseService(
            store, store, store, store, orderPublisher, clock, ids, settings);
    final EventManagementService eventManagement = new EventManagementService(
            store, store, provisioningPublisher, clock, ids, settings);
    final EventCatalogService catalog = new EventCatalogService(store, store, clock, settings);
    final OrderQueryService orderQueries = new OrderQueryService(store);

    Event seedEvent() {
        return store.seedEnabledEvent(EVENT_ID, "Concert", NOW.plus(Duration.ofDays(10)), DEFINITION);
    }

    Mono<PurchaseResult> purchase(String customerId, String key, String... ticketIds) {
        return purchases.startPurchase(command(customerId, key, ticketIds));
    }

    static StartPurchaseCommand command(String customerId, String key, String... ticketIds) {
        return new StartPurchaseCommand(customerId, EVENT_ID, List.of(ticketIds), key, CORRELATION);
    }

    static String key(int sequence) {
        return "purchase-key-%08d".formatted(sequence);
    }

    static CreateEventCommand createEvent(String key, Instant startsAt, int capacity, InventoryDefinition definition) {
        return new CreateEventCommand(ADMIN, key, "Concert", "Arena", startsAt, capacity, definition, CORRELATION);
    }
}
