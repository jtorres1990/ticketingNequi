package com.nequi.ticketing.application.usecase;

import static com.nequi.ticketing.application.usecase.ApiFixture.DEFINITION;
import static com.nequi.ticketing.application.usecase.ApiFixture.EVENT_ID;
import static com.nequi.ticketing.application.usecase.ApiFixture.NOW;
import static com.nequi.ticketing.application.usecase.Rejections.invalid;
import static com.nequi.ticketing.application.usecase.Rejections.rejected;
import static com.nequi.ticketing.application.usecase.Rejections.value;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nequi.ticketing.application.port.in.AvailabilityQuery;
import com.nequi.ticketing.application.port.in.AvailabilityView;
import com.nequi.ticketing.application.port.in.AvailableTicketView;
import com.nequi.ticketing.application.port.in.EventSummaryPage;
import com.nequi.ticketing.application.port.in.EventSummaryView;
import com.nequi.ticketing.application.port.in.ListEventsQuery;
import com.nequi.ticketing.application.port.out.AvailableTicketPage;
import com.nequi.ticketing.application.port.out.EventCatalog;
import com.nequi.ticketing.application.port.out.ProvisioningSnapshot;
import com.nequi.ticketing.application.port.out.TicketInventory;
import com.nequi.ticketing.domain.error.DomainErrorCode;
import com.nequi.ticketing.domain.event.Event;
import com.nequi.ticketing.domain.event.InventoryDefinition;
import com.nequi.ticketing.domain.event.InventoryDefinition.Row;
import com.nequi.ticketing.domain.event.InventoryDefinition.Section;
import com.nequi.ticketing.domain.event.InventoryLimits;
import com.nequi.ticketing.domain.ticket.Ticket;
import com.nequi.ticketing.domain.ticket.Ticket.TicketState;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

class EventCatalogServiceTest {

    private ApiFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new ApiFixture();
    }

    // ------------------------------------------------------------------ API-002

    @Test
    @DisplayName("AC-002 FR-002 BR-022 lists future ENABLED Events with soldOut, excluding past and PROVISIONING Events")
    void listsFutureEnabledEventsWithSoldOut() {
        InventoryDefinition small = new InventoryDefinition(List.of(new Section("A", List.of(new Row("1", 2)))), List.of());
        fixture.store.seedEnabledEvent("e-later", "Later", NOW.plus(Duration.ofDays(20)), small);
        fixture.store.seedEnabledEvent("e-sold-out", "Sold out", NOW.plus(Duration.ofDays(5)), small);
        fixture.store.setTicket("e-sold-out", "A-1-1", TicketState.SOLD, "o-1");
        fixture.store.setTicket("e-sold-out", "A-1-2", TicketState.RESERVED, "o-2");
        fixture.store.seedEnabledEvent("e-past", "Past", NOW.minus(Duration.ofDays(1)), small);
        fixture.store.seedSnapshot(new ProvisioningSnapshot(Event.create("e-provisioning", "Soon", "V",
                NOW.plus(Duration.ofDays(2)), 2, small, NOW, InventoryLimits.DEPLOYED), NOW, 0, null, null));

        EventSummaryPage page = value(fixture.catalog.listEvents(new ListEventsQuery(null, null)));

        assertThat(page.items()).extracting(EventSummaryView::eventId).containsExactly("e-sold-out", "e-later");
        assertThat(page.items()).extracting(EventSummaryView::soldOut).containsExactly(true, false);
        assertThat(page.items().getFirst().capacity()).isEqualTo(2);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("BR-012 DS-005 an Event whose only remaining tickets are COMPLIMENTARY is sold out")
    void complimentaryTicketsAreNotCommercialAvailability() {
        InventoryDefinition courtesy = new InventoryDefinition(List.of(new Section("A", List.of(new Row("1", 2)))),
                List.of(new InventoryDefinition.ComplimentaryRange("A", "1", 2, 2)));
        fixture.store.seedEnabledEvent("e-courtesy", "Courtesy", NOW.plus(Duration.ofDays(5)), courtesy);
        fixture.store.setTicket("e-courtesy", "A-1-1", TicketState.SOLD, "o-1");

        EventSummaryPage page = value(fixture.catalog.listEvents(new ListEventsQuery(null, null)));

        assertThat(page.items()).singleElement().satisfies(summary -> assertThat(summary.soldOut()).isTrue());
    }

    @Test
    @DisplayName("FR-002 OpenAPI v2 the listing is paginated by limit and opaque cursor in start order")
    void paginatesTheListing() {
        for (int day = 1; day <= 3; day++) {
            fixture.store.seedEnabledEvent("e-" + day, "Event " + day, NOW.plus(Duration.ofDays(day)), DEFINITION);
        }

        EventSummaryPage first = value(fixture.catalog.listEvents(new ListEventsQuery(2, null)));
        EventSummaryPage second = value(fixture.catalog.listEvents(new ListEventsQuery(2, first.nextCursor())));

        assertThat(first.items()).extracting(EventSummaryView::eventId).containsExactly("e-1", "e-2");
        assertThat(first.nextCursor()).isNotNull();
        assertThat(second.items()).extracting(EventSummaryView::eventId).containsExactly("e-3");
        assertThat(second.nextCursor()).isNull();
    }

    @Test
    @DisplayName("ADR-035 VAL-015 an invalid listing limit or cursor is VALIDATION_ERROR")
    void rejectsInvalidListingParameters() {
        invalid(fixture.catalog.listEvents(new ListEventsQuery(0, null)));
        invalid(fixture.catalog.listEvents(new ListEventsQuery(101, null)));
        invalid(fixture.catalog.listEvents(new ListEventsQuery(null, "not-a-cursor")));
    }

    @Test
    @DisplayName("BR-022 the listing never returns an Event that became past after the index read")
    void filtersEventsThatAreNoLongerFuture() {
        EventCatalog catalog = mock(EventCatalog.class);
        Event past = fixture.store.seedEnabledEvent("e-past", "Past", NOW.minusSeconds(1), DEFINITION);
        when(catalog.listEnabledUpcoming(eq(NOW), eq(20), isNull()))
                .thenReturn(Mono.just(new com.nequi.ticketing.application.port.out.EnabledEventPage(List.of(past), null)));
        EventCatalogService service = new EventCatalogService(catalog, fixture.store, fixture.clock, fixture.settings);

        assertThat(value(service.listEvents(new ListEventsQuery(null, null))).items()).isEmpty();
    }

    // ------------------------------------------------------------------ API-003

    @Test
    @DisplayName("AC-010 FR-012 BR-012 availability returns the AVAILABLE count, generatedAt and a page of AVAILABLE tickets only")
    void returnsCountAndPageOfAvailableTickets() {
        fixture.seedEvent();
        fixture.store.setTicket(EVENT_ID, "A-1-1", TicketState.SOLD, "o-1");
        fixture.store.setTicket(EVENT_ID, "A-1-2", TicketState.RESERVED, "o-2");
        fixture.store.setTicket(EVENT_ID, "A-1-3", TicketState.PENDING_CONFIRMATION, "o-3");

        AvailabilityView view = value(fixture.catalog.getAvailability(new AvailabilityQuery(EVENT_ID, null, 5, null)));

        assertThat(view.eventId()).isEqualTo(EVENT_ID);
        assertThat(view.name()).isEqualTo("Concert");
        assertThat(view.capacity()).isEqualTo(25);
        assertThat(view.sections()).containsExactly("A", "B");
        assertThat(view.availableCount()).isEqualTo(21);
        assertThat(view.generatedAt()).isEqualTo(NOW);
        assertThat(view.informative()).isTrue();
        assertThat(view.sectionFilter()).isNull();
        assertThat(view.items()).extracting(AvailableTicketView::ticketId)
                .containsExactly("A-1-4", "A-1-5", "A-1-6", "A-1-7", "A-1-8");
        assertThat(view.items().getFirst()).isEqualTo(new AvailableTicketView("A-1-4", "A", "1", 4));
        assertThat(view.nextCursor()).isNotNull();
    }

    @Test
    @DisplayName("AC-010 VAL-015 the page never exceeds the requested size and the cursor walks every AVAILABLE ticket once")
    void cursorWalksEveryAvailableTicket() {
        fixture.seedEvent();
        List<String> seen = new ArrayList<>();
        String cursor = null;
        do {
            AvailabilityView page = value(fixture.catalog.getAvailability(new AvailabilityQuery(EVENT_ID, null, 10, cursor)));
            assertThat(page.items()).hasSizeLessThanOrEqualTo(10);
            page.items().forEach(ticket -> seen.add(ticket.ticketId()));
            cursor = page.nextCursor();
        } while (cursor != null);

        assertThat(seen).hasSize(24).doesNotHaveDuplicates().doesNotContain("B-1-5");
    }

    @Test
    @DisplayName("FR-012 ADR-040 the page can be filtered by section and uses the default page size of 50")
    void filtersBySection() {
        fixture.seedEvent();

        AvailabilityView view = value(fixture.catalog.getAvailability(new AvailabilityQuery(EVENT_ID, "B", null, null)));

        assertThat(view.sectionFilter()).isEqualTo("B");
        assertThat(view.items()).extracting(AvailableTicketView::ticketId).containsExactly("B-1-1", "B-1-2", "B-1-3", "B-1-4");
        assertThat(view.availableCount()).isEqualTo(24);
        assertThat(view.nextCursor()).isNull();
    }

    @Test
    @DisplayName("AC-018 BR-017 consulting availability never reserves, never changes ticket states and never creates Orders")
    void availabilityIsReadOnly() {
        fixture.seedEvent();
        List<Ticket> before = fixture.store.tickets(EVENT_ID);

        value(fixture.catalog.getAvailability(new AvailabilityQuery(EVENT_ID, null, 100, null)));
        fixture.clock.advance(Duration.ofMinutes(15));
        value(fixture.catalog.getAvailability(new AvailabilityQuery(EVENT_ID, "A", 3, null)));

        assertThat(fixture.store.tickets(EVENT_ID)).containsExactlyInAnyOrderElementsOf(before);
        assertThat(fixture.store.orders()).isEmpty();
        assertThat(fixture.store.reservationAttempts()).isZero();
    }

    @Test
    @DisplayName("AC-038 FG-005 BR-025 the availability of a past ENABLED Event answers informatively")
    void pastEventAvailabilityAnswers() {
        fixture.store.seedEnabledEvent("e-past", "Past", NOW.minus(Duration.ofHours(2)), DEFINITION);

        AvailabilityView view = value(fixture.catalog.getAvailability(new AvailabilityQuery("e-past", null, null, null)));

        assertThat(view.availableCount()).isEqualTo(24);
        assertThat(view.informative()).isTrue();
        assertThat(value(fixture.catalog.listEvents(new ListEventsQuery(null, null))).items()).isEmpty();
    }

    @Test
    @DisplayName("AC-039 ERR-013 BR-027 availability of a missing, PROVISIONING or FAILED Event is EVENT_NOT_FOUND")
    void availabilityOfInvisibleEvents() {
        Event provisioning = Event.create("e-prov", "P", "V", NOW.plus(Duration.ofDays(2)), 25, DEFINITION, NOW,
                InventoryLimits.DEPLOYED);
        fixture.store.seedSnapshot(new ProvisioningSnapshot(provisioning, NOW, 0, null, null));
        fixture.store.seedSnapshot(new ProvisioningSnapshot(Event.create("e-fail", "F", "V", NOW.plus(Duration.ofDays(2)),
                25, DEFINITION, NOW, InventoryLimits.DEPLOYED).fail(), NOW, 0, null, NOW));

        for (String eventId : List.of("missing", "e-prov", "e-fail")) {
            rejected(fixture.catalog.getAvailability(new AvailabilityQuery(eventId, null, null, null)),
                    DomainErrorCode.EVENT_NOT_FOUND);
        }
    }

    @Test
    @DisplayName("AC-048 ERR-020 VAL-015 an unknown section, an invalid cursor or an invalid page size is VALIDATION_ERROR")
    void rejectsInvalidAvailabilityParameters() {
        fixture.seedEvent();
        AvailabilityView sectionA = value(fixture.catalog.getAvailability(new AvailabilityQuery(EVENT_ID, "A", 2, null)));

        invalid(fixture.catalog.getAvailability(new AvailabilityQuery(EVENT_ID, "Z", null, null)));
        invalid(fixture.catalog.getAvailability(new AvailabilityQuery(EVENT_ID, null, null, "garbage")));
        invalid(fixture.catalog.getAvailability(new AvailabilityQuery(EVENT_ID, "B", 2, sectionA.nextCursor())));
        invalid(fixture.catalog.getAvailability(new AvailabilityQuery(EVENT_ID, null, 0, null)));
        invalid(fixture.catalog.getAvailability(new AvailabilityQuery(EVENT_ID, null, 101, null)));
        invalid(fixture.catalog.getAvailability(new AvailabilityQuery(null, null, null, null)));
    }

    // ------------------------------------------------------------------ ADR-040 count cache

    @Test
    @DisplayName("ADR-040 AC-010 the count is cached for 1 s per Event and recomputed afterwards")
    void cachesTheCountForOneSecond() {
        fixture.seedEvent();
        AvailabilityQuery query = new AvailabilityQuery(EVENT_ID, null, 1, null);

        AvailabilityView first = value(fixture.catalog.getAvailability(query));
        fixture.store.setTicket(EVENT_ID, "A-1-1", TicketState.SOLD, "o-1");
        fixture.clock.advance(Duration.ofMillis(999));
        AvailabilityView cached = value(fixture.catalog.getAvailability(query));
        fixture.clock.advance(Duration.ofMillis(1));
        AvailabilityView refreshed = value(fixture.catalog.getAvailability(query));

        assertThat(first.availableCount()).isEqualTo(24);
        assertThat(cached.availableCount()).isEqualTo(24);
        assertThat(cached.generatedAt()).isEqualTo(NOW);
        assertThat(refreshed.availableCount()).isEqualTo(23);
        assertThat(refreshed.generatedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(fixture.store.countQueries()).isEqualTo(2);
    }

    @Test
    @DisplayName("ADR-040 simultaneous requests for the same Event share a single count in flight")
    void simultaneousRequestsShareOneCount() {
        Event event = fixture.seedEvent();
        TicketInventory inventory = mock(TicketInventory.class);
        Sinks.One<Long> count = Sinks.one();
        AtomicInteger subscriptions = new AtomicInteger();
        when(inventory.countAvailable(event)).thenReturn(count.asMono().doOnSubscribe(subscription -> subscriptions.incrementAndGet()));
        when(inventory.findAvailablePage(eq(event), isNull(), anyInt(), isNull()))
                .thenReturn(Mono.just(new AvailableTicketPage(List.of(), null)));
        EventCatalogService service = new EventCatalogService(fixture.store, inventory, fixture.clock, fixture.settings);
        AvailabilityQuery query = new AvailabilityQuery(EVENT_ID, null, null, null);

        StepVerifier.create(Mono.zip(service.getAvailability(query), service.getAvailability(query)))
                .then(() -> count.tryEmitValue(7L))
                .assertNext(both -> {
                    assertThat(both.getT1().availableCount()).isEqualTo(7);
                    assertThat(both.getT2().availableCount()).isEqualTo(7);
                })
                .verifyComplete();
        assertThat(subscriptions.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("ADR-040 a failed count is not cached")
    void failedCountIsNotCached() {
        Event event = fixture.seedEvent();
        TicketInventory inventory = mock(TicketInventory.class);
        when(inventory.countAvailable(event)).thenReturn(Mono.error(new IllegalStateException("index unavailable")),
                Mono.just(5L));
        when(inventory.findAvailablePage(any(), any(), anyInt(), any()))
                .thenReturn(Mono.just(new AvailableTicketPage(List.of(), null)));
        EventCatalogService service = new EventCatalogService(fixture.store, inventory, fixture.clock, fixture.settings);
        AvailabilityQuery query = new AvailabilityQuery(EVENT_ID, null, null, null);

        StepVerifier.create(service.getAvailability(query)).expectError(IllegalStateException.class).verify(Duration.ofSeconds(5));
        assertThat(value(service.getAvailability(query)).availableCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("ADR-040 an empty count is reported as an error and not cached")
    void emptyCountIsAnError() {
        Event event = fixture.seedEvent();
        TicketInventory inventory = mock(TicketInventory.class);
        when(inventory.countAvailable(event)).thenReturn(Mono.empty(), Mono.just(3L));
        when(inventory.findAvailablePage(any(), any(), anyInt(), any()))
                .thenReturn(Mono.just(new AvailableTicketPage(List.of(), null)));
        EventCatalogService service = new EventCatalogService(fixture.store, inventory, fixture.clock, fixture.settings);
        AvailabilityQuery query = new AvailabilityQuery(EVENT_ID, null, null, null);

        StepVerifier.create(service.getAvailability(query)).expectError(IllegalStateException.class).verify(Duration.ofSeconds(5));
        assertThat(value(service.getAvailability(query)).availableCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("ADR-040 the count cache is bounded: expired entries are swept once it grows")
    void sweepsExpiredCacheEntries() {
        AvailableCountCache cache = new AvailableCountCache(fixture.clock, Duration.ofSeconds(1));
        for (int index = 0; index < 1_025; index++) {
            value(cache.get("event-" + index, () -> Mono.just(1L)));
        }
        fixture.clock.advance(Duration.ofSeconds(2));

        value(cache.get("event-new", () -> Mono.just(1L)));

        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("ADR-040 the sold-out probe treats an empty probe result as sold out")
    void emptyProbeIsSoldOut() {
        Event event = fixture.seedEvent();
        TicketInventory inventory = mock(TicketInventory.class);
        when(inventory.hasAvailable(event)).thenReturn(Mono.empty());
        EventCatalogService service = new EventCatalogService(fixture.store, inventory, fixture.clock, fixture.settings);

        assertThat(value(service.listEvents(new ListEventsQuery(null, null))).items())
                .singleElement().satisfies(summary -> assertThat(summary.soldOut()).isTrue());
    }
}
