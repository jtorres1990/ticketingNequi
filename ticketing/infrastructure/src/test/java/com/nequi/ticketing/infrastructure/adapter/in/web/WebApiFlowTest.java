package com.nequi.ticketing.infrastructure.adapter.in.web;

import static com.nequi.ticketing.infrastructure.adapter.in.web.TestTokenIssuer.ADMIN;
import static com.nequi.ticketing.infrastructure.adapter.in.web.TestTokenIssuer.ADMIN_CUSTOMER;
import static com.nequi.ticketing.infrastructure.adapter.in.web.TestTokenIssuer.CUSTOMER_A;
import static com.nequi.ticketing.infrastructure.adapter.in.web.TestTokenIssuer.CUSTOMER_B;
import static com.nequi.ticketing.infrastructure.adapter.in.web.TestTokenIssuer.NO_GROUPS;
import static org.assertj.core.api.Assertions.assertThat;

import com.nequi.ticketing.application.port.out.ProvisioningSnapshot;
import com.nequi.ticketing.application.port.out.PublishResult;
import com.nequi.ticketing.application.port.out.PublisherAvailability;
import com.nequi.ticketing.application.testdouble.InMemoryTicketingStore;
import com.nequi.ticketing.application.testdouble.MutableClock;
import com.nequi.ticketing.application.testdouble.RecordingOrderQueuePublisher;
import com.nequi.ticketing.application.testdouble.RecordingProvisioningQueuePublisher;
import com.nequi.ticketing.application.testdouble.SequentialIdGenerator;
import com.nequi.ticketing.application.usecase.ApiUseCaseSettings;
import com.nequi.ticketing.application.usecase.EventCatalogService;
import com.nequi.ticketing.application.usecase.EventManagementService;
import com.nequi.ticketing.application.usecase.OrderQueryService;
import com.nequi.ticketing.application.usecase.PurchaseService;
import com.nequi.ticketing.domain.event.Event;
import com.nequi.ticketing.domain.event.InventoryDefinition;
import com.nequi.ticketing.domain.event.InventoryDefinition.ComplimentaryRange;
import com.nequi.ticketing.domain.event.InventoryDefinition.Row;
import com.nequi.ticketing.domain.event.InventoryDefinition.Section;
import com.nequi.ticketing.domain.event.InventoryLimits;
import com.nequi.ticketing.domain.ticket.Ticket.TicketState;
import com.nequi.ticketing.infrastructure.adapter.in.web.TestTokenIssuer.Identity;
import com.nequi.ticketing.infrastructure.adapter.in.web.WebApiTestServer.Reply;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Web layer tests of API-001 to API-006 over the real {@code api} use cases (CMP-003 to CMP-006) and the
 * in-memory doubles of the outbound ports, with the five deterministic identities of ADR-033 and every
 * exchange validated against OpenAPI v2 (INC-008). The authoritative level of AC-026, AC-027 and AC-028 is
 * here (plan §7); the other criteria complement the use case tests of INC-003 with the HTTP contract.
 */
class WebApiFlowTest {

    static final Instant NOW = WebApiTestServer.NOW;
    static final String EVENT_ID = "e0000000-0000-4000-8000-000000000001";
    static final String PAST_EVENT_ID = "e0000000-0000-4000-8000-000000000002";
    static final String SOLD_OUT_EVENT_ID = "e0000000-0000-4000-8000-000000000003";
    static final String UNKNOWN_EVENT_ID = "e0000000-0000-4000-8000-0000000000ff";

    /** A: rows 1 and 2 with 10 seats each; B: row 1 with 5 seats, seat 5 complimentary. Capacity 25. */
    static final InventoryDefinition DEFINITION = new InventoryDefinition(
            List.of(
                    new Section("A", List.of(new Row("1", 10), new Row("2", 10))),
                    new Section("B", List.of(new Row("1", 5)))),
            List.of(new ComplimentaryRange("B", "1", 5, 5)));

    static final String EVENT_BODY = """
            {"name":"Concert","venue":"Arena","startsAt":"2026-11-11T20:00:00Z","capacity":25,
             "inventory":{"sections":[{"code":"A","rows":[{"label":"1","seats":10},{"label":"2","seats":10}]},
                                      {"code":"B","rows":[{"label":"1","seats":5}]}],
                          "complimentary":[{"section":"B","row":"1","fromSeat":5,"toSeat":5}]}}
            """;

    private static TestTokenIssuer issuer;

    private MutableClock clock;
    private InMemoryTicketingStore store;
    private RecordingOrderQueuePublisher orderPublisher;
    private RecordingProvisioningQueuePublisher provisioningPublisher;
    private WebApiTestServer server;
    private int keys;

    @BeforeAll
    static void startIssuer() {
        issuer = TestTokenIssuer.start(NOW);
    }

    @AfterAll
    static void stopIssuer() {
        issuer.close();
    }

    @BeforeEach
    void startServer() {
        clock = new MutableClock(NOW);
        store = new InMemoryTicketingStore();
        orderPublisher = new RecordingOrderQueuePublisher();
        provisioningPublisher = new RecordingProvisioningQueuePublisher();
        SequentialIdGenerator ids = new SequentialIdGenerator();
        ApiUseCaseSettings settings = ApiUseCaseSettings.DEPLOYED;
        EventManagementService management = new EventManagementService(store, store, provisioningPublisher, clock, ids, settings);
        EventCatalogService catalog = new EventCatalogService(store, store, clock, settings);
        PurchaseService purchases = new PurchaseService(store, store, store, store, orderPublisher, clock, ids, settings);
        server = WebApiTestServer.start(issuer, new WebApiUseCases(
                management, catalog, management, catalog, purchases, new OrderQueryService(store)));
        store.seedEnabledEvent(EVENT_ID, "Concert", NOW.plus(Duration.ofDays(10)), DEFINITION);
    }

    @AfterEach
    void stopServer() {
        server.close();
        // NFR-003: a blocking call detected on an event loop thread would surface as an unclassified failure (500)
        assertThat(server.events.unclassified).isEmpty();
    }

    // ---------------------------------------------------------------- API-001 / API-006

    @Test
    @DisplayName("AC-001 API-001 ADMIN creates an Event: 202, Location of API-006, PROVISIONING and MSG-002 requested")
    void createEvent() {
        Reply reply = post("/api/v1/events", ADMIN, key(), EVENT_BODY, true);

        assertThat(reply.status()).isEqualTo(202);
        JsonNode body = reply.json();
        String eventId = body.get("eventId").stringValue();
        assertThat(reply.header("Location")).isEqualTo("/api/v1/events/" + eventId + "/provisioning");
        assertThat(reply.header("Idempotency-Replayed")).isNull();
        assertThat(body.get("provisioningStatus").stringValue()).isEqualTo("PROVISIONING");
        assertThat(body.get("capacity").intValue()).isEqualTo(25);
        assertThat(body.get("complimentaryTickets").intValue()).isEqualTo(1);
        assertThat(body.get("provisionedTickets").intValue()).isZero();
        assertThat(body.get("createdAt").stringValue()).isEqualTo(NOW.toString());
        assertThat(body.get("enabledAt").isNull()).isTrue();
        assertThat(body.get("failureCause").isNull()).isTrue();
        assertThat(provisioningPublisher.published()).singleElement()
                .satisfies(message -> assertThat(message.eventId()).isEqualTo(eventId));
    }

    @Test
    @DisplayName("AC-041 API-001 replay with the same key and content: 200 Idempotency-Replayed, same eventId, nothing created")
    void createEventReplay() {
        String key = key();
        String eventId = post("/api/v1/events", ADMIN, key, EVENT_BODY, true).json().get("eventId").stringValue();

        Reply replay = post("/api/v1/events", ADMIN, key, EVENT_BODY.replace("2026-11-11T20:00:00Z", "2026-11-11T15:00:00-05:00"), true);

        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.header("Idempotency-Replayed")).isEqualTo("true");
        assertThat(replay.header("Location")).isEqualTo("/api/v1/events/" + eventId + "/provisioning");
        assertThat(replay.json().get("eventId").stringValue()).isEqualTo(eventId);
        assertThat(provisioningPublisher.published()).hasSize(1);
    }

    @Test
    @DisplayName("AC-042 ERR-019 API-001 same key with another content: 422 IDEMPOTENCY_KEY_REUSED without effects")
    void createEventKeyReused() {
        String key = key();
        post("/api/v1/events", ADMIN, key, EVENT_BODY, true);

        Reply reply = post("/api/v1/events", ADMIN, key, EVENT_BODY.replace("Concert", "Other"), true);

        assertProblem(reply, 422, "IDEMPOTENCY_KEY_REUSED");
        assertThat(provisioningPublisher.published()).hasSize(1);
    }

    @Test
    @DisplayName("BR-032 API-001 the idempotency key is bound to the ADMIN subject: another ADMIN with the same key creates its own Event")
    void createEventKeyScopedBySubject() {
        String key = key();
        String first = post("/api/v1/events", ADMIN, key, EVENT_BODY, true).json().get("eventId").stringValue();

        Reply other = post("/api/v1/events", ADMIN_CUSTOMER, key, EVENT_BODY, true);

        assertThat(other.status()).isEqualTo(202);
        assertThat(other.json().get("eventId").stringValue()).isNotEqualTo(first);
    }

    @Test
    @DisplayName("AC-017 AC-033 ERR-011 API-001 invalid Event: 400 VALIDATION_ERROR with errors, nothing created")
    void createEventInvalid() {
        Reply tooLarge = post("/api/v1/events", ADMIN, key(),
                EVENT_BODY.replace("\"capacity\":25", "\"capacity\":50001"), false);
        Reply mismatch = post("/api/v1/events", ADMIN, key(),
                EVENT_BODY.replace("\"capacity\":25", "\"capacity\":24"), true);
        Reply past = post("/api/v1/events", ADMIN, key(),
                EVENT_BODY.replace("2026-11-11T20:00:00Z", "2026-10-01T20:00:00Z"), true);
        Reply overlapping = post("/api/v1/events", ADMIN, key(), EVENT_BODY.replace(
                "\"complimentary\":[{\"section\":\"B\",\"row\":\"1\",\"fromSeat\":5,\"toSeat\":5}]",
                "\"complimentary\":[{\"section\":\"B\",\"row\":\"1\",\"fromSeat\":4,\"toSeat\":5},"
                        + "{\"section\":\"B\",\"row\":\"1\",\"fromSeat\":5,\"toSeat\":5}]"), true);

        assertValidation(tooLarge, "capacity");
        assertValidation(mismatch, "capacity");
        assertValidation(past, "startsAt");
        assertValidation(overlapping, "inventory.complimentary");
        assertThat(provisioningPublisher.published()).isEmpty();
    }

    @Test
    @DisplayName("AC-039 FR-020 API-006 ADMIN observes PROVISIONING; the CUSTOMER does not see the Event until ENABLED")
    void provisioningEventInvisibleToCustomers() {
        String eventId = post("/api/v1/events", ADMIN, key(), EVENT_BODY, true).json().get("eventId").stringValue();

        Reply status = get("/api/v1/events/" + eventId + "/provisioning", ADMIN, true);
        Reply availability = get("/api/v1/events/" + eventId + "/availability", CUSTOMER_A, true);
        Reply listed = get("/api/v1/events", CUSTOMER_A, true);
        Reply purchase = post("/api/v1/orders", CUSTOMER_A, key(), purchaseBody(eventId, "A-1-1"), true);

        assertThat(status.status()).isEqualTo(200);
        assertThat(status.json().get("provisioningStatus").stringValue()).isEqualTo("PROVISIONING");
        assertProblem(availability, 404, "EVENT_NOT_FOUND");
        assertThat(listed.body()).doesNotContain(eventId);
        assertProblem(purchase, 404, "EVENT_NOT_FOUND");
    }

    @Test
    @DisplayName("AC-040 API-006 a FAILED Event shows FAILED with cause PROVISIONING_FAILED to the ADMIN only")
    void failedEventStatus() {
        String failedId = "e0000000-0000-4000-8000-0000000000fa";
        Event failed = Event.create(failedId, "Failed", "Arena", NOW.plus(Duration.ofDays(5)), 25, DEFINITION,
                NOW.minus(Duration.ofHours(1)), InventoryLimits.DEPLOYED).fail();
        store.seedSnapshot(new ProvisioningSnapshot(failed, NOW.minus(Duration.ofHours(1)), 0, null, NOW.minusSeconds(60)));

        Reply status = get("/api/v1/events/" + failedId + "/provisioning", ADMIN, true);

        assertThat(status.status()).isEqualTo(200);
        assertThat(status.json().get("provisioningStatus").stringValue()).isEqualTo("FAILED");
        assertThat(status.json().get("failureCause").stringValue()).isEqualTo("PROVISIONING_FAILED");
        assertThat(status.json().get("failedAt").stringValue()).isEqualTo(NOW.minusSeconds(60).toString());
        assertProblem(get("/api/v1/events/" + failedId + "/availability", ADMIN, true), 404, "EVENT_NOT_FOUND");
    }

    @Test
    @DisplayName("API-006 ENABLED Event: provisionedTickets equals capacity; unknown or malformed identifiers: 404 EVENT_NOT_FOUND")
    void provisioningStatusOfEnabledAndUnknown() {
        Reply enabled = get("/api/v1/events/" + EVENT_ID + "/provisioning", ADMIN, true);
        Reply unknown = get("/api/v1/events/" + UNKNOWN_EVENT_ID + "/provisioning", ADMIN, true);
        Reply malformed = get("/api/v1/events/not-a-uuid/provisioning", ADMIN, false);

        assertThat(enabled.json().get("provisioningStatus").stringValue()).isEqualTo("ENABLED");
        assertThat(enabled.json().get("provisionedTickets").intValue()).isEqualTo(25);
        assertThat(enabled.json().get("enabledAt").isNull()).isFalse();
        assertProblem(unknown, 404, "EVENT_NOT_FOUND");
        assertProblem(malformed, 404, "EVENT_NOT_FOUND");
    }

    // ---------------------------------------------------------------- API-002 / API-003

    @Test
    @DisplayName("AC-002 API-002 lists future ENABLED Events with soldOut, without past Events, with limit and cursor")
    void listEvents() {
        store.seedEnabledEvent(PAST_EVENT_ID, "Past", NOW.plus(Duration.ofDays(1)), DEFINITION);
        Event soldOut = store.seedEnabledEvent(SOLD_OUT_EVENT_ID, "Sold out", NOW.plus(Duration.ofDays(20)),
                new InventoryDefinition(List.of(new Section("S", List.of(new Row("1", 1)))), List.of()));
        store.setTicket(soldOut.eventId(), "S-1-1", TicketState.SOLD, "00000000-0000-4000-8000-0000000000aa");
        clock.set(NOW.plus(Duration.ofDays(2)));

        Reply page = get("/api/v1/events?limit=1", CUSTOMER_A, true);
        Reply next = get("/api/v1/events?limit=1&cursor=" + page.json().get("nextCursor").stringValue(), ADMIN, true);

        assertThat(page.status()).isEqualTo(200);
        assertThat(page.json().get("items")).hasSize(1);
        assertThat(page.json().get("items").get(0).get("eventId").stringValue()).isEqualTo(EVENT_ID);
        assertThat(page.json().get("items").get(0).get("soldOut").booleanValue()).isFalse();
        assertThat(page.json().get("items").get(0).has("availableCount")).isFalse();
        assertThat(next.json().get("items").get(0).get("eventId").stringValue()).isEqualTo(SOLD_OUT_EVENT_ID);
        assertThat(next.json().get("items").get(0).get("soldOut").booleanValue()).isTrue();
        assertThat(page.body() + next.body()).doesNotContain(PAST_EVENT_ID);
    }

    @Test
    @DisplayName("AC-010 API-003 count of AVAILABLE, generatedAt and a page of AVAILABLE Tickets only, filtered by section, with cursor")
    void availability() {
        store.setTicket(EVENT_ID, "A-1-1", TicketState.RESERVED, "00000000-0000-4000-8000-0000000000aa");

        Reply all = get("/api/v1/events/" + EVENT_ID + "/availability?pageSize=5", CUSTOMER_A, true);
        Reply section = get("/api/v1/events/" + EVENT_ID + "/availability?section=B", ADMIN, true);

        assertThat(all.status()).isEqualTo(200);
        JsonNode body = all.json();
        assertThat(body.get("availableCount").longValue()).isEqualTo(23);
        assertThat(body.get("generatedAt").stringValue()).isEqualTo(NOW.toString());
        assertThat(body.get("informative").booleanValue()).isTrue();
        assertThat(body.get("items")).hasSize(5);
        assertThat(body.has("sectionFilter")).isFalse();
        assertThat(body.toString()).doesNotContain("A-1-1\"");
        List<String> sections = new ArrayList<>();
        body.get("sections").forEach(code -> sections.add(code.stringValue()));
        assertThat(sections).containsExactly("A", "B");
        assertThat(body.get("nextCursor").isString()).isTrue();
        Reply next = get("/api/v1/events/" + EVENT_ID + "/availability?pageSize=5&cursor="
                + body.get("nextCursor").stringValue(), CUSTOMER_A, true);
        assertThat(next.json().get("items")).hasSize(5);
        assertThat(section.json().get("sectionFilter").stringValue()).isEqualTo("B");
        assertThat(section.json().get("items")).hasSize(4);
        section.json().get("items").forEach(ticket -> assertThat(ticket.get("section").stringValue()).isEqualTo("B"));
    }

    @Test
    @DisplayName("AC-038 API-003 a past ENABLED Event still answers informatively by identifier")
    void availabilityOfPastEvent() {
        clock.set(NOW.plus(Duration.ofDays(11)));

        Reply reply = get("/api/v1/events/" + EVENT_ID + "/availability", CUSTOMER_B, true);

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.json().get("availableCount").longValue()).isEqualTo(24);
    }

    @Test
    @DisplayName("AC-048 ERR-020 VAL-015 API-003 invalid cursor, unknown section or page size out of range: 400 VALIDATION_ERROR")
    void availabilityInvalidParameters() {
        assertValidation(get("/api/v1/events/" + EVENT_ID + "/availability?cursor=bm90LWEtY3Vyc29y", CUSTOMER_A, true), "cursor");
        assertValidation(get("/api/v1/events/" + EVENT_ID + "/availability?section=Z", CUSTOMER_A, true), "section");
        assertValidation(get("/api/v1/events/" + EVENT_ID + "/availability?section=A-1", CUSTOMER_A, false), "section");
        assertValidation(get("/api/v1/events/" + EVENT_ID + "/availability?pageSize=101", CUSTOMER_A, false), "pageSize");
        assertValidation(get("/api/v1/events/" + EVENT_ID + "/availability?pageSize=0", CUSTOMER_A, false), "pageSize");
        assertValidation(get("/api/v1/events/" + EVENT_ID + "/availability?pageSize=ten", CUSTOMER_A, false), "pageSize");
        assertValidation(get("/api/v1/events/" + EVENT_ID + "/availability?cursor=" + "c".repeat(513), CUSTOMER_A, false), "cursor");
        assertValidation(get("/api/v1/events?limit=0", CUSTOMER_A, false), "limit");
        assertValidation(get("/api/v1/events?cursor=bm9wZQ", CUSTOMER_A, true), "cursor");
        assertProblem(get("/api/v1/events/" + UNKNOWN_EVENT_ID + "/availability", CUSTOMER_A, true), 404, "EVENT_NOT_FOUND");
        assertProblem(get("/api/v1/events/bad-id/availability", CUSTOMER_A, false), 404, "EVENT_NOT_FOUND");
    }

    // ---------------------------------------------------------------- API-004 / API-005

    @Test
    @DisplayName("AC-003 AC-004 API-004 purchase: 201 with Location, Order CREATED with its Reservation, MSG-001 enqueued; API-005 returns it")
    void purchaseAndQuery() {
        Reply created = post("/api/v1/orders", CUSTOMER_A, key(), purchaseBody(EVENT_ID, "A-1-1", "A-1-2"), true);

        assertThat(created.status()).isEqualTo(201);
        JsonNode order = created.json();
        String orderId = order.get("orderId").stringValue();
        assertThat(created.header("Location")).isEqualTo("/api/v1/orders/" + orderId);
        assertThat(order.get("status").stringValue()).isEqualTo("CREATED");
        assertThat(order.get("eventId").stringValue()).isEqualTo(EVENT_ID);
        assertThat(order.get("reservationExpiresAt").stringValue()).isEqualTo(NOW.plus(Duration.ofMinutes(10)).toString());
        assertThat(order.has("failureCause")).isFalse();
        assertThat(order.has("customerId")).isFalse();
        assertThat(orderPublisher.published()).singleElement()
                .satisfies(message -> assertThat(message.orderId()).isEqualTo(orderId));

        Reply queried = get("/api/v1/orders/" + orderId, CUSTOMER_A, true);
        assertThat(queried.status()).isEqualTo(200);
        assertThat(queried.json().get("orderId").stringValue()).isEqualTo(orderId);
        assertThat(queried.json().get("status").stringValue()).isEqualTo("CREATED");
    }

    @Test
    @DisplayName("AC-041 ADR-027 API-004 replay with the same key and content: 200 Idempotency-Replayed with the existing Order")
    void purchaseReplay() {
        String key = key();
        String orderId = post("/api/v1/orders", CUSTOMER_A, key, purchaseBody(EVENT_ID, "A-1-1"), true)
                .json().get("orderId").stringValue();

        Reply replay = post("/api/v1/orders", CUSTOMER_A, key, purchaseBody(EVENT_ID, "A-1-1"), true);
        Reply reused = post("/api/v1/orders", CUSTOMER_A, key, purchaseBody(EVENT_ID, "A-1-2"), true);

        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.header("Idempotency-Replayed")).isEqualTo("true");
        assertThat(replay.json().get("orderId").stringValue()).isEqualTo(orderId);
        assertProblem(reused, 422, "IDEMPOTENCY_KEY_REUSED");
        assertThat(orderPublisher.published()).hasSize(1);
    }

    @Test
    @DisplayName("AC-043 ERR-014 API-004 second Order of the same CUSTOMER and Event with another key: 409 ACTIVE_ORDER_EXISTS")
    void secondActiveOrder() {
        post("/api/v1/orders", CUSTOMER_A, key(), purchaseBody(EVENT_ID, "A-1-1"), true);

        Reply second = post("/api/v1/orders", CUSTOMER_A, key(), purchaseBody(EVENT_ID, "A-1-2"), true);
        Reply otherCustomer = post("/api/v1/orders", CUSTOMER_B, key(), purchaseBody(EVENT_ID, "A-1-2"), true);

        assertProblem(second, 409, "ACTIVE_ORDER_EXISTS");
        assertThat(otherCustomer.status()).isEqualTo(201);
    }

    @Test
    @DisplayName("AC-016 ERR-002 API-004 a Ticket not AVAILABLE: 409 TICKETS_UNAVAILABLE with unavailableTicketIds, nothing reserved")
    void ticketsUnavailable() {
        store.setTicket(EVENT_ID, "A-1-2", TicketState.SOLD, "00000000-0000-4000-8000-0000000000aa");

        Reply reply = post("/api/v1/orders", CUSTOMER_A, key(), purchaseBody(EVENT_ID, "A-1-1", "A-1-2"), true);

        assertProblem(reply, 409, "TICKETS_UNAVAILABLE");
        assertThat(reply.json().get("unavailableTicketIds").get(0).stringValue()).isEqualTo("A-1-2");
        assertThat(get("/api/v1/events/" + EVENT_ID + "/availability", CUSTOMER_A, true)
                .json().get("availableCount").longValue()).isEqualTo(23);
        assertThat(orderPublisher.published()).isEmpty();
    }

    @Test
    @DisplayName("ERR-021 UNKNOWN_TICKETS API-004 Tickets not in the Event definition: 422 with unknownTicketIds")
    void unknownTickets() {
        Reply reply = post("/api/v1/orders", CUSTOMER_A, key(), purchaseBody(EVENT_ID, "A-1-1", "Z-9-9"), true);

        assertProblem(reply, 422, "UNKNOWN_TICKETS");
        assertThat(reply.json().get("unknownTicketIds").get(0).stringValue()).isEqualTo("Z-9-9");
    }

    @Test
    @DisplayName("AC-037 ERR-012 ERR-013 API-004 past Event: 409 EVENT_NOT_ON_SALE; unknown Event: 404 EVENT_NOT_FOUND")
    void eventNotOnSaleAndNotFound() {
        Reply unknown = post("/api/v1/orders", CUSTOMER_A, key(), purchaseBody(UNKNOWN_EVENT_ID, "A-1-1"), true);
        clock.set(NOW.plus(Duration.ofDays(10)));
        Reply past = post("/api/v1/orders", CUSTOMER_A, key(), purchaseBody(EVENT_ID, "A-1-1"), true);

        assertProblem(unknown, 404, "EVENT_NOT_FOUND");
        assertProblem(past, 409, "EVENT_NOT_ON_SALE");
    }

    @Test
    @DisplayName("AC-047 BR-031 API-004 precedence over HTTP: past Event wins over the active Order; validation wins over everything")
    void rejectionPrecedence() {
        post("/api/v1/orders", CUSTOMER_A, key(), purchaseBody(EVENT_ID, "A-1-1"), true);
        clock.set(NOW.plus(Duration.ofDays(10)));

        Reply pastWithActiveOrder = post("/api/v1/orders", CUSTOMER_A, key(), purchaseBody(EVENT_ID, "A-1-2"), true);
        Reply invalidOnUnknownEvent = post("/api/v1/orders", CUSTOMER_A, key(),
                purchaseBody(UNKNOWN_EVENT_ID, "A-1-1", "A-1-1"), false);

        assertProblem(pastWithActiveOrder, 409, "EVENT_NOT_ON_SALE");
        assertValidation(invalidOnUnknownEvent, "ticketIds");
    }

    @Test
    @DisplayName("AC-032 ERR-010 VAL-012 API-004 0, 11 or repeated Tickets: 400 VALIDATION_ERROR on ticketIds, nothing reserved")
    void invalidOrderSize() {
        List<String> eleven = new ArrayList<>();
        for (int seat = 1; seat <= 11; seat++) {
            eleven.add("A-1-" + seat);
        }

        assertValidation(post("/api/v1/orders", CUSTOMER_A, key(), "{\"eventId\":\"" + EVENT_ID + "\",\"ticketIds\":[]}", false), "ticketIds");
        assertValidation(post("/api/v1/orders", CUSTOMER_A, key(), purchaseBody(EVENT_ID, eleven.toArray(String[]::new)), false), "ticketIds");
        assertValidation(post("/api/v1/orders", CUSTOMER_A, key(), purchaseBody(EVENT_ID, "A-1-1", "A-1-1"), false), "ticketIds");
        assertThat(orderPublisher.published()).isEmpty();
        assertThat(get("/api/v1/events/" + EVENT_ID + "/availability", CUSTOMER_A, true)
                .json().get("availableCount").longValue()).isEqualTo(24);
    }

    @Test
    @DisplayName("AC-046 ERR-015 FR-024 API-004 queue publication unavailable: 503 SERVICE_UNAVAILABLE with Retry-After before reserving")
    void queueUnavailable() {
        orderPublisher.setAvailability(PublisherAvailability.unavailable(Duration.ofMillis(4_200)));

        Reply reply = post("/api/v1/orders", CUSTOMER_A, key(), purchaseBody(EVENT_ID, "A-1-1"), true);

        assertProblem(reply, 503, "SERVICE_UNAVAILABLE");
        assertThat(reply.header("Retry-After")).isEqualTo("5");
        assertThat(orderPublisher.published()).isEmpty();
        assertThat(get("/api/v1/events/" + EVENT_ID + "/availability", CUSTOMER_A, true)
                .json().get("availableCount").longValue()).isEqualTo(24);
    }

    @Test
    @DisplayName("AC-022 ALT-006 API-004 definitive enqueue failure: 201 with the Order FAILED and cause PROCESSING_UNAVAILABLE")
    void enqueueFailure() {
        orderPublisher.script(PublishResult.FAILED);

        Reply reply = post("/api/v1/orders", CUSTOMER_A, key(), purchaseBody(EVENT_ID, "A-1-1"), true);

        assertThat(reply.status()).isEqualTo(201);
        assertThat(reply.json().get("status").stringValue()).isEqualTo("FAILED");
        assertThat(reply.json().get("failureCause").get("code").stringValue()).isEqualTo("PROCESSING_UNAVAILABLE");
        assertThat(reply.json().get("failureCause").get("message").stringValue())
                .isEqualTo("The order could not be queued for processing.");
    }

    @Test
    @DisplayName("IV-009 ADR-025 API-004 compensation cancelled by a Ticket condition: quarantine, 201 with the current Order CREATED")
    void compensationQuarantineAnswers201() {
        orderPublisher.script(PublishResult.FAILED);
        orderPublisher.onPublish(message -> store.setTicket(EVENT_ID, "A-1-2", TicketState.SOLD, "corrupted-order"));

        Reply reply = post("/api/v1/orders", CUSTOMER_A, key(), purchaseBody(EVENT_ID, "A-1-1", "A-1-2"), true);

        assertThat(reply.status()).isEqualTo(201);
        assertThat(reply.json().get("status").stringValue()).isEqualTo("CREATED");
        assertThat(reply.body()).doesNotContain("quarantine").doesNotContain("corrupted");
        String orderId = reply.json().get("orderId").stringValue();
        assertThat(store.order(orderId).orElseThrow().order().quarantinedAt()).isEqualTo(NOW);
        Reply queried = get("/api/v1/orders/" + orderId, CUSTOMER_A, true);
        assertThat(queried.json().get("status").stringValue()).isEqualTo("CREATED");
        assertThat(queried.body()).doesNotContain("quarantine");
        assertProblem(post("/api/v1/orders", CUSTOMER_A, key(), purchaseBody(EVENT_ID, "A-1-3"), true), 409, "ACTIVE_ORDER_EXISTS");
    }

    @Test
    @DisplayName("AC-027 BR-023 ALT-007 ERR-006 ERR-009 VAL-011 API-005 foreign, non-existent and malformed Order: the same 404 ORDER_NOT_FOUND")
    void foreignOrderIndistinguishable() {
        String orderId = post("/api/v1/orders", CUSTOMER_A, key(), purchaseBody(EVENT_ID, "A-1-1"), true)
                .json().get("orderId").stringValue();

        Reply foreign = get("/api/v1/orders/" + orderId, CUSTOMER_B, true);
        Reply missing = get("/api/v1/orders/00000000-0000-4000-8000-0000000000ff", CUSTOMER_B, true);
        Reply malformed = get("/api/v1/orders/not-an-order", CUSTOMER_B, true);

        for (Reply reply : List.of(foreign, missing, malformed)) {
            assertProblem(reply, 404, "ORDER_NOT_FOUND");
        }
        assertThat(withoutTraceId(foreign)).isEqualTo(withoutTraceId(missing)).isEqualTo(withoutTraceId(malformed));
        assertThat(foreign.body()).doesNotContain(orderId).doesNotContain("customer-a");
        assertThat(get("/api/v1/orders/" + orderId, CUSTOMER_A, true).status()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- AC-026 / AC-028

    @Test
    @DisplayName("AC-028 FR-019 authorization matrix of the five ADR-033 identities over API-001 to API-006")
    void authorizationMatrix() {
        String orderId = post("/api/v1/orders", CUSTOMER_A, key(), purchaseBody(EVENT_ID, "A-1-1"), true)
                .json().get("orderId").stringValue();
        Map<Identity, String> seats = Map.of(CUSTOMER_B, "A-2-1", ADMIN_CUSTOMER, "A-2-2", ADMIN, "A-2-3", NO_GROUPS, "A-2-4");
        record Expectation(Identity identity, boolean createEvent, boolean read, boolean buy, boolean provisioning) {
        }
        List<Expectation> matrix = List.of(
                new Expectation(ADMIN, true, true, false, true),
                new Expectation(CUSTOMER_B, false, true, true, false),
                new Expectation(ADMIN_CUSTOMER, true, true, true, true),
                new Expectation(NO_GROUPS, false, false, false, false));

        for (Expectation expected : matrix) {
            Identity who = expected.identity();
            assertAuthorized(post("/api/v1/events", who, key(), EVENT_BODY, true), expected.createEvent(), 202);
            assertAuthorized(get("/api/v1/events", who, true), expected.read(), 200);
            assertAuthorized(get("/api/v1/events/" + EVENT_ID + "/availability", who, true), expected.read(), 200);
            assertAuthorized(get("/api/v1/events/" + EVENT_ID + "/provisioning", who, true), expected.provisioning(), 200);
            assertAuthorized(post("/api/v1/orders", who, key(), purchaseBody(EVENT_ID, seats.get(who)), true), expected.buy(), 201);
            assertAuthorized(get("/api/v1/orders/" + orderId, who, true), expected.buy(), 404);
        }
        assertAuthorized(get("/api/v1/orders/" + orderId, CUSTOMER_A, true), true, 200);
        assertAuthorized(post("/api/v1/events", CUSTOMER_A, key(), EVENT_BODY, true), false, 0);
        assertThat(orderPublisher.published()).hasSize(3);
        assertThat(provisioningPublisher.published()).hasSize(2);
    }

    @Test
    @DisplayName("AC-028 BR-024 the one-active-Order rule applies to the authenticated CUSTOMER identity, also for an ADMIN that is CUSTOMER")
    void activeOrderRulePerCustomerIdentity() {
        assertThat(post("/api/v1/orders", ADMIN_CUSTOMER, key(), purchaseBody(EVENT_ID, "A-1-1"), true).status()).isEqualTo(201);

        assertProblem(post("/api/v1/orders", ADMIN_CUSTOMER, key(), purchaseBody(EVENT_ID, "A-1-2"), true), 409, "ACTIVE_ORDER_EXISTS");
        assertThat(post("/api/v1/orders", CUSTOMER_A, key(), purchaseBody(EVENT_ID, "A-1-2"), true).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("AC-026 BR-016 BR-026 no operation modifies an Event or adds complimentary Tickets after creation: denied, Event unchanged")
    void noOperationModifiesAnEvent() {
        String base = "/api/v1/events/" + EVENT_ID;
        List<Reply> attempts = List.of(
                outsideContract(HttpMethod.PUT, base, ADMIN, EVENT_BODY),
                outsideContract(HttpMethod.PATCH, base, ADMIN, "{\"capacity\":30}"),
                outsideContract(HttpMethod.DELETE, base, ADMIN, null),
                outsideContract(HttpMethod.POST, base + "/complimentary", ADMIN,
                        "{\"section\":\"A\",\"row\":\"1\",\"fromSeat\":1,\"toSeat\":2}"),
                outsideContract(HttpMethod.POST, base + "/tickets", ADMIN_CUSTOMER, "{\"ticketId\":\"A-1-1\"}"),
                outsideContract(HttpMethod.PUT, base + "/provisioning", ADMIN, "{\"provisioningStatus\":\"ENABLED\"}"));

        for (Reply reply : attempts) {
            assertThat(reply.status()).isEqualTo(403);
            assertThat(reply.code()).isEqualTo("FORBIDDEN");
            assertThat(reply.contentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        }
        Reply availability = get(base + "/availability?section=A", CUSTOMER_A, true);
        assertThat(availability.json().get("availableCount").longValue()).isEqualTo(24);
        assertThat(availability.json().get("capacity").intValue()).isEqualTo(25);
    }

    // ---------------------------------------------------------------- helpers

    private Reply post(String path, Identity who, String key, String body, boolean validRequest) {
        return server.send(server.client.post().uri(path)
                .headers(headers -> {
                    if (who != null) {
                        headers.setBearerAuth(server.token(who));
                    }
                    if (key != null) {
                        headers.set("Idempotency-Key", key);
                    }
                })
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body), validRequest);
    }

    private Reply get(String path, Identity who, boolean validRequest) {
        return server.send(server.client.get().uri(path)
                .headers(headers -> headers.setBearerAuth(server.token(who))), validRequest);
    }

    private Reply outsideContract(HttpMethod method, String path, Identity who, String body) {
        var request = server.client.method(method).uri(path).headers(headers -> headers.setBearerAuth(server.token(who)));
        var spec = body == null ? request : request.contentType(MediaType.APPLICATION_JSON).bodyValue(body);
        return new Reply(spec.exchange().expectBody().returnResult());
    }

    private String key() {
        return "web-flow-key-%08d".formatted(++keys);
    }

    static String purchaseBody(String eventId, String... ticketIds) {
        StringBuilder tickets = new StringBuilder();
        for (String ticketId : ticketIds) {
            tickets.append(tickets.isEmpty() ? "" : ",").append('"').append(ticketId).append('"');
        }
        return "{\"eventId\":\"" + eventId + "\",\"ticketIds\":[" + tickets + "]}";
    }

    static void assertProblem(Reply reply, int status, String code) {
        assertThat(reply.status()).as(reply.body()).isEqualTo(status);
        assertThat(reply.contentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        JsonNode body = reply.json();
        assertThat(body.get("code").stringValue()).isEqualTo(code);
        assertThat(body.get("status").intValue()).isEqualTo(status);
        assertThat(body.get("type").stringValue()).isEqualTo("about:blank");
        assertThat(body.get("title").stringValue()).isNotBlank();
        assertThat(body.get("traceId").stringValue()).matches("[0-9a-f]{32}");
        assertThat(reply.body()).doesNotContain("Exception").doesNotContain("at com.").doesNotContain("java.");
    }

    static void assertValidation(Reply reply, String field) {
        assertProblem(reply, 400, "VALIDATION_ERROR");
        List<String> fields = new ArrayList<>();
        reply.json().get("errors").forEach(error -> fields.add(error.get("field").stringValue()));
        assertThat(fields).contains(field);
    }

    private static void assertAuthorized(Reply reply, boolean authorized, int expectedStatus) {
        if (authorized) {
            assertThat(reply.status()).as(reply.body()).isEqualTo(expectedStatus);
        } else {
            assertProblem(reply, 403, "FORBIDDEN");
        }
    }

    private static String withoutTraceId(Reply reply) {
        ObjectNode body = (ObjectNode) reply.json();
        body.remove("traceId");
        return body.toString();
    }
}
