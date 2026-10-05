package com.nequi.ticketing.infrastructure.adapter.in.web;

import static com.nequi.ticketing.infrastructure.adapter.in.web.WebApiFlowTest.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nequi.ticketing.application.port.in.AvailabilityQuery;
import com.nequi.ticketing.application.port.in.AvailabilityView;
import com.nequi.ticketing.application.port.in.CreateEventCommand;
import com.nequi.ticketing.application.port.in.EventCreationResult;
import com.nequi.ticketing.application.port.in.EventSummaryPage;
import com.nequi.ticketing.application.port.in.ListEventsQuery;
import com.nequi.ticketing.application.port.in.ProvisioningStatusView;
import com.nequi.ticketing.application.port.in.PurchaseResult;
import com.nequi.ticketing.application.port.in.StartPurchaseCommand;
import com.nequi.ticketing.infrastructure.adapter.in.web.WebApiTestServer.Reply;
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
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import reactor.core.publisher.Mono;

/**
 * CMP-001 syntactic validation (VAL-016, ERR-010, ERR-011, ADR-035): structural rules of OpenAPI v2 checked by the
 * adapter before any use case, with one {@code VALIDATION_ERROR} that lists every invalid input in {@code errors};
 * and the mapping of valid requests to commands and queries.
 */
class ApiRequestsTest {

    private static TestTokenIssuer issuer;

    private WebApiMocks mocks;
    private WebApiTestServer server;
    private int keys;

    @BeforeAll
    static void startIssuer() {
        issuer = TestTokenIssuer.start(WebApiTestServer.NOW);
    }

    @AfterAll
    static void stopIssuer() {
        issuer.close();
    }

    @BeforeEach
    void startServer() {
        mocks = new WebApiMocks();
        when(mocks.createEvent.createEvent(any()))
                .thenReturn(Mono.just(new EventCreationResult(WebApiMocks.provisioning(), false)));
        when(mocks.startPurchase.startPurchase(any()))
                .thenReturn(Mono.just(new PurchaseResult(WebApiMocks.order(WebApiMocks.ORDER_ID), false)));
        when(mocks.listEvents.listEvents(any())).thenReturn(Mono.just(new EventSummaryPage(List.of(), null)));
        server = WebApiTestServer.start(issuer, mocks.useCases());
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    @DisplayName("VAL-016 ADR-027 Idempotency-Key absent, malformed or repeated: 400 on Idempotency-Key, no use case invoked")
    void idempotencyKeyHeader() {
        assertFields(createEvent(null, MediaType.APPLICATION_JSON, WebApiMocks.EVENT_BODY), "Idempotency-Key");
        assertFields(createEvent("short-key", MediaType.APPLICATION_JSON, WebApiMocks.EVENT_BODY), "Idempotency-Key");
        assertFields(createEvent("invalid key with spaces!", MediaType.APPLICATION_JSON, WebApiMocks.EVENT_BODY), "Idempotency-Key");
        assertFields(createEvent("k".repeat(65), MediaType.APPLICATION_JSON, WebApiMocks.EVENT_BODY), "Idempotency-Key");
        Reply repeated = server.send(server.client.post().uri("/api/v1/orders").headers(headers -> {
            headers.setBearerAuth(server.token(TestTokenIssuer.CUSTOMER_A));
            headers.add("Idempotency-Key", WebApiMocks.key(1));
            headers.add("Idempotency-Key", WebApiMocks.key(2));
        }).contentType(MediaType.APPLICATION_JSON).bodyValue(WebApiFlowTest.purchaseBody(WebApiMocks.EVENT_ID, "A-1-1")), false);
        assertFields(repeated, "Idempotency-Key");
        verifyNoInteractions(mocks.createEvent, mocks.startPurchase);
    }

    @Test
    @DisplayName("ADR-035 body must be a single JSON object sent as application/json; every invalid input is reported at once")
    void bodyEnvelope() {
        assertFields(createEvent(key(), MediaType.TEXT_PLAIN, WebApiMocks.EVENT_BODY), "Content-Type");
        assertFields(createEvent(key(), MediaType.APPLICATION_JSON, ""), "body");
        assertFields(createEvent(key(), MediaType.APPLICATION_JSON, "{\"name\":"), "body");
        assertFields(createEvent(key(), MediaType.APPLICATION_JSON, "{} {}"), "body");
        assertFields(createEvent(key(), MediaType.APPLICATION_JSON, "{\"name\":\"a\",\"name\":\"b\"}"), "body");
        assertFields(createEvent(key(), MediaType.APPLICATION_JSON, "[]"), "body");
        assertFields(createEvent(null, MediaType.TEXT_PLAIN, "{\"venue\":7}"), "Idempotency-Key", "Content-Type",
                "name", "venue", "startsAt", "capacity", "inventory");
        assertFields(createEvent(null, MediaType.APPLICATION_JSON, ""), "Idempotency-Key", "body");
        verifyNoInteractions(mocks.createEvent);
    }

    @Test
    @DisplayName("ERR-011 API-001 CreateEventRequest structure: members, types, formats and patterns of OpenAPI v2")
    void createEventStructure() {
        String base = WebApiMocks.EVENT_BODY;
        assertFields(createEvent(key(), MediaType.APPLICATION_JSON, base.replace("\"name\":\"Concert\"", "\"name\":\"Concert\",\"extra\":1")), "extra");
        assertFields(createEvent(key(), MediaType.APPLICATION_JSON, base.replace("\"Concert\"", "\"\"")), "name");
        assertFields(createEvent(key(), MediaType.APPLICATION_JSON, base.replace("\"Concert\"", "\"" + "x".repeat(201) + "\"")), "name");
        assertFields(createEvent(key(), MediaType.APPLICATION_JSON, base.replace("\"Arena\"", "[\"Arena\"]")), "venue");
        assertFields(createEvent(key(), MediaType.APPLICATION_JSON, base.replace("\"2026-11-11T20:00:00Z\"", "\"2026-11-11T20:00:00\"")), "startsAt");
        assertFields(createEvent(key(), MediaType.APPLICATION_JSON, base.replace("\"2026-11-11T20:00:00Z\"", "1762891200")), "startsAt");
        assertFields(createEvent(key(), MediaType.APPLICATION_JSON, base.replace("\"capacity\":25", "\"capacity\":\"25\"")), "capacity");
        assertFields(createEvent(key(), MediaType.APPLICATION_JSON, base.replace("\"capacity\":25", "\"capacity\":25.5")), "capacity");
        assertFields(createEvent(key(), MediaType.APPLICATION_JSON, base.replace("\"capacity\":25", "\"capacity\":100000000000")), "capacity");
        assertFields(createEvent(key(), MediaType.APPLICATION_JSON, "{\"name\":\"a\",\"venue\":\"b\",\"startsAt\":\"2026-11-11T20:00:00Z\",\"capacity\":1,\"inventory\":7}"), "inventory");
        assertFields(createEvent(key(), MediaType.APPLICATION_JSON, "{\"name\":\"a\",\"venue\":\"b\",\"startsAt\":\"2026-11-11T20:00:00Z\",\"capacity\":1,\"inventory\":{}}"), "inventory.sections");
        assertFields(createEvent(key(), MediaType.APPLICATION_JSON, inventory("{\"sections\":{},\"other\":[]}")), "inventory.sections", "inventory.other");
        assertFields(createEvent(key(), MediaType.APPLICATION_JSON, inventory("{\"sections\":[]}")), "inventory.sections");
        assertFields(createEvent(key(), MediaType.APPLICATION_JSON, inventory("{\"sections\":[{\"rows\":[{\"label\":\"1\",\"seats\":1}]}]}")),
                "inventory.sections[0].code");
        assertFields(createEvent(key(), MediaType.APPLICATION_JSON, inventory("{\"sections\":[1,{\"code\":\"A-1\",\"rows\":[]},{\"code\":\"B\"},{\"code\":\"C\",\"rows\":{}}]}")),
                "inventory.sections[0]", "inventory.sections[1].code", "inventory.sections[1].rows",
                "inventory.sections[2].rows", "inventory.sections[3].rows");
        assertFields(createEvent(key(), MediaType.APPLICATION_JSON, inventory("{\"sections\":[{\"code\":\"A\",\"x\":1,\"rows\":[\"r\",{\"label\":\"1234567\",\"seats\":2},{\"label\":\"1\",\"seats\":\"2\"},{\"label\":\"2\",\"seats\":2,\"y\":0}]}]}")),
                "inventory.sections[0].x", "inventory.sections[0].rows[0]", "inventory.sections[0].rows[1].label",
                "inventory.sections[0].rows[2].seats", "inventory.sections[0].rows[3].y");
        assertFields(createEvent(key(), MediaType.APPLICATION_JSON, inventory("{\"sections\":[{\"code\":\"A\",\"rows\":[{\"label\":\"1\"}]}],\"complimentary\":{}}")),
                "inventory.sections[0].rows[0].seats", "inventory.complimentary");
        assertFields(createEvent(key(), MediaType.APPLICATION_JSON, inventory("{\"sections\":[{\"code\":\"A\",\"rows\":[{\"label\":\"1\",\"seats\":2}]}],\"complimentary\":[0,{\"section\":\"A\",\"row\":\"1\",\"fromSeat\":1},{\"section\":\"A!\",\"row\":\"\",\"fromSeat\":1.5,\"toSeat\":\"2\",\"z\":1}]}")),
                "inventory.complimentary[0]", "inventory.complimentary[1].toSeat", "inventory.complimentary[2].z",
                "inventory.complimentary[2].section", "inventory.complimentary[2].row",
                "inventory.complimentary[2].fromSeat", "inventory.complimentary[2].toSeat");
        assertFields(createEvent(key(), MediaType.APPLICATION_JSON, inventory("{\"sections\":[{\"code\":\"A\",\"rows\":[{\"label\":\"1\",\"seats\":2}]}],\"complimentary\":[{\"section\":\"A!\",\"row\":\"\",\"fromSeat\":1.5,\"toSeat\":\"2\"}]}")),
                "inventory.complimentary[0].section", "inventory.complimentary[0].row",
                "inventory.complimentary[0].fromSeat", "inventory.complimentary[0].toSeat");
        verifyNoInteractions(mocks.createEvent);
    }

    @Test
    @DisplayName("FR-001 API-001 a valid request becomes the command: ADMIN subject, key, normalised instant and definition")
    void createEventCommand() {
        String body = inventory("{\"sections\":[{\"code\":\"A\",\"rows\":[{\"label\":\"1\",\"seats\":2}]}]}")
                .replace("2026-11-11T20:00:00Z", "2026-11-11T15:00:00-05:00");
        String key = key();

        Reply reply = createEvent(key, MediaType.parseMediaType("application/json;charset=UTF-8"), body);

        assertThat(reply.status()).isEqualTo(202);
        ArgumentCaptor<CreateEventCommand> command = ArgumentCaptor.forClass(CreateEventCommand.class);
        verify(mocks.createEvent).createEvent(command.capture());
        assertThat(command.getValue().adminSubject()).isEqualTo("admin");
        assertThat(command.getValue().idempotencyKey()).isEqualTo(key);
        assertThat(command.getValue().startsAt()).isEqualTo(Instant.parse("2026-11-11T20:00:00Z"));
        assertThat(command.getValue().capacity()).isEqualTo(1);
        assertThat(command.getValue().inventory().sectionCodes()).containsExactly("A");
        assertThat(command.getValue().inventory().complimentaryRanges()).isEmpty();
    }

    @Test
    @DisplayName("ERR-010 API-004 StartPurchaseRequest structure: eventId UUID, ticketIds array of TicketId, no other member")
    void purchaseStructure() {
        assertFields(purchase("{\"ticketIds\":[\"A-1-1\"]}"), "eventId");
        assertFields(purchase("{\"eventId\":\"not-a-uuid\",\"ticketIds\":[\"A-1-1\"]}"), "eventId");
        assertFields(purchase("{\"eventId\":7,\"ticketIds\":[\"A-1-1\"]}"), "eventId");
        assertFields(purchase("{\"eventId\":\"" + WebApiMocks.EVENT_ID + "\"}"), "ticketIds");
        assertFields(purchase("{\"eventId\":\"" + WebApiMocks.EVENT_ID + "\",\"ticketIds\":\"A-1-1\"}"), "ticketIds");
        assertFields(purchase("{\"eventId\":\"" + WebApiMocks.EVENT_ID + "\",\"ticketIds\":[\"A-1-1\",\"A_1_2\",3,\"A-1-12345\"]}"),
                "ticketIds[1]", "ticketIds[2]", "ticketIds[3]");
        assertFields(purchase("{\"eventId\":\"" + WebApiMocks.EVENT_ID + "\",\"ticketIds\":[\"A-1-1\"],\"customerId\":\"x\"}"),
                "customerId");
        assertFields(purchase("\"text\""), "body");
        verifyNoInteractions(mocks.startPurchase);
    }

    @Test
    @DisplayName("VAL-011 API-004 a valid request becomes the command: owner from the token subject, never from the body")
    void purchaseCommand() {
        Reply reply = purchase(WebApiFlowTest.purchaseBody(WebApiMocks.EVENT_ID, "A-1-1", "B-12-1000"));

        assertThat(reply.status()).isEqualTo(201);
        ArgumentCaptor<StartPurchaseCommand> command = ArgumentCaptor.forClass(StartPurchaseCommand.class);
        verify(mocks.startPurchase).startPurchase(command.capture());
        assertThat(command.getValue().customerId()).isEqualTo("customer-a");
        assertThat(command.getValue().eventId()).isEqualTo(WebApiMocks.EVENT_ID);
        assertThat(command.getValue().ticketIds()).containsExactly("A-1-1", "B-12-1000");
    }

    @Test
    @DisplayName("VAL-015 API-002 API-003 query parameters: integers, cursor length and section code; valid ones reach the query")
    void queryParameters() {
        when(mocks.availability.getAvailability(any())).thenReturn(Mono.just(new AvailabilityView(
                WebApiMocks.EVENT_ID, "Concert", "Arena", WebApiTestServer.NOW.plusSeconds(86_400), 25, List.of("A"), 0,
                WebApiTestServer.NOW, true, "A", List.of(), null)));
        when(mocks.provisioningStatus.getProvisioningStatus(any())).thenReturn(Mono.just(new ProvisioningStatusView(
                WebApiMocks.EVENT_ID, com.nequi.ticketing.domain.event.Event.ProvisioningStatus.ENABLED, 25, 1, 25,
                WebApiTestServer.NOW, WebApiTestServer.NOW, null, null)));

        assertFields(get("/api/v1/events?limit=abc"), "limit");
        assertFields(get("/api/v1/events?limit=99999999999"), "limit");
        assertFields(get("/api/v1/events?limit=9999999999"), "limit");
        assertFields(get("/api/v1/events?limit=1.5&cursor="), "limit", "cursor");
        assertFields(get("/api/v1/events?cursor=" + "c".repeat(1025)), "cursor");
        assertFields(get("/api/v1/events/" + WebApiMocks.EVENT_ID + "/availability?section=ABCDEFGHI&pageSize=x"), "section", "pageSize");
        Reply listed = get("/api/v1/events?limit=5&cursor=" + "c".repeat(1024));
        Reply availability = get("/api/v1/events/" + WebApiMocks.EVENT_ID + "/availability?section=A&pageSize=7&cursor=abc");
        Reply provisioning = get("/api/v1/events/" + WebApiMocks.EVENT_ID.toUpperCase() + "/provisioning");

        assertThat(listed.status()).isEqualTo(200);
        assertThat(availability.status()).isEqualTo(200);
        assertThat(availability.json().get("sectionFilter").stringValue()).isEqualTo("A");
        assertThat(provisioning.status()).isEqualTo(200);
        verify(mocks.listEvents).listEvents(new ListEventsQuery(5, "c".repeat(1024)));
        verify(mocks.availability).getAvailability(new AvailabilityQuery(WebApiMocks.EVENT_ID, "A", 7, "abc"));
        verify(mocks.provisioningStatus).getProvisioningStatus(WebApiMocks.EVENT_ID.toUpperCase());
    }

    @Test
    @DisplayName("ADR-035 a validation rejection always carries at least one field error")
    void rejectionRequiresErrors() {
        assertThatThrownBy(() -> ApiRejection.invalid(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApiRejection.FieldError(null, "reason")).isInstanceOf(NullPointerException.class);
        assertThat(ApiRejection.invalid("field", "reason").errors()).singleElement()
                .isEqualTo(new ApiRejection.FieldError("field", "reason"));
        assertThat(ApiRejection.rateLimited(java.time.Duration.ofSeconds(2)).retryAfter()).isPresent();
        assertThat(ApiRejection.payloadTooLarge().retryAfter()).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    private Reply createEvent(String key, MediaType contentType, String body) {
        return server.send(server.client.post().uri("/api/v1/events").headers(headers -> {
            headers.setBearerAuth(server.token(TestTokenIssuer.ADMIN));
            if (key != null) {
                headers.set("Idempotency-Key", key);
            }
            headers.set(HttpHeaders.CONTENT_TYPE, contentType.toString());
        }).bodyValue(body), false);
    }

    private Reply purchase(String body) {
        return server.send(server.client.post().uri("/api/v1/orders").headers(headers -> {
            headers.setBearerAuth(server.token(TestTokenIssuer.CUSTOMER_A));
            headers.set("Idempotency-Key", key());
        }).contentType(MediaType.APPLICATION_JSON).bodyValue(body), false);
    }

    private Reply get(String path) {
        return server.send(server.client.get().uri(path)
                .headers(headers -> headers.setBearerAuth(server.token(TestTokenIssuer.ADMIN_CUSTOMER))), false);
    }

    private static String inventory(String inventory) {
        return "{\"name\":\"a\",\"venue\":\"b\",\"startsAt\":\"2026-11-11T20:00:00Z\",\"capacity\":1,\"inventory\":"
                + inventory + "}";
    }

    private String key() {
        return "web-request-key-%06d".formatted(++keys);
    }

    private static void assertFields(Reply reply, String... fields) {
        assertProblem(reply, 400, "VALIDATION_ERROR");
        List<String> reported = new ArrayList<>();
        reply.json().get("errors").forEach(error -> {
            reported.add(error.get("field").stringValue());
            assertThat(error.get("reason").stringValue()).isNotBlank();
        });
        assertThat(reported).as(reply.body()).containsExactlyInAnyOrder(fields);
        assertThat(Map.of("detail", reply.json().get("detail").stringValue())).containsEntry("detail", "The request is invalid.");
    }
}
