package com.nequi.ticketing.bootstrap.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.nequi.ticketing.infrastructure.adapter.in.web.TestTokenIssuer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** HTTP client of the six operations of OpenAPI v2 for the component integration tests. */
public final class TicketingApi {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final WebTestClient client;
    private final TestTokenIssuer tokens;

    public TicketingApi(WebTestClient client, TestTokenIssuer tokens) {
        this.client = client;
        this.tokens = tokens;
    }

    /** One reply: status, headers and body. */
    public record Reply(int status, HttpHeaders headers, String body) {

        public JsonNode json() {
            return JSON.readTree(body);
        }

        public String text(String field) {
            return json().get(field).stringValue();
        }
    }

    /** Event definition with one section of {@code rows} rows of {@code seats} seats, seat 1 of row 1 complimentary. */
    public static String eventBody(String name, Instant startsAt, int rows, int seats) {
        StringBuilder rowList = new StringBuilder();
        for (int row = 1; row <= rows; row++) {
            rowList.append(row == 1 ? "" : ",").append("{\"label\":\"").append(row).append("\",\"seats\":")
                    .append(seats).append('}');
        }
        return "{\"name\":\"" + name + "\",\"venue\":\"Arena\",\"startsAt\":\""
                + startsAt.truncatedTo(ChronoUnit.SECONDS) + "\",\"capacity\":" + (rows * seats)
                + ",\"inventory\":{\"sections\":[{\"code\":\"A\",\"rows\":[" + rowList + "]}],"
                + "\"complimentary\":[{\"section\":\"A\",\"row\":\"1\",\"fromSeat\":1,\"toSeat\":1}]}}";
    }

    public static String key() {
        return "it-" + UUID.randomUUID().toString().replace("-", "");
    }

    public Reply createEvent(String body, String traceParent) {
        return exchange(client.post().uri("/api/v1/events")
                .headers(headers -> {
                    headers.setBearerAuth(tokens.token(TestTokenIssuer.ADMIN));
                    headers.set("Idempotency-Key", key());
                    if (traceParent != null) {
                        headers.set("traceparent", traceParent);
                    }
                })
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body));
    }

    public Reply provisioning(String eventId) {
        return exchange(client.get().uri("/api/v1/events/{id}/provisioning", eventId)
                .headers(headers -> headers.setBearerAuth(tokens.token(TestTokenIssuer.ADMIN))));
    }

    /** Creates an Event and waits until it is ENABLED (AC-001); returns its identifier. */
    public String createEnabledEvent(int rows, int seats, Duration timeout) {
        Reply created = createEvent(eventBody("Concert " + UUID.randomUUID(), Instant.now().plus(Duration.ofDays(30)),
                rows, seats), null);
        assertThat(created.status()).as(created.body()).isEqualTo(202);
        String eventId = created.text("eventId");
        awaitProvisioning(eventId, "ENABLED", timeout);
        return eventId;
    }

    public JsonNode awaitProvisioning(String eventId, String status, Duration timeout) {
        JsonNode[] last = new JsonNode[1];
        await().atMost(timeout).pollInterval(Duration.ofMillis(250)).untilAsserted(() -> {
            Reply reply = provisioning(eventId);
            assertThat(reply.status()).as(reply.body()).isEqualTo(200);
            last[0] = reply.json();
            assertThat(last[0].get("provisioningStatus").stringValue()).isEqualTo(status);
        });
        return last[0];
    }

    public Reply availability(TestTokenIssuer.Identity identity, String eventId) {
        return exchange(client.get().uri("/api/v1/events/{id}/availability?pageSize=100", eventId)
                .headers(headers -> headers.setBearerAuth(tokens.token(identity))));
    }

    public long availableCount(String eventId) {
        Reply reply = availability(TestTokenIssuer.CUSTOMER_A, eventId);
        assertThat(reply.status()).as(reply.body()).isEqualTo(200);
        return reply.json().get("availableCount").longValue();
    }

    /** Identifiers of the AVAILABLE Tickets of the first page (up to 100). */
    public java.util.List<String> availableTicketIds(String eventId) {
        Reply reply = availability(TestTokenIssuer.CUSTOMER_A, eventId);
        assertThat(reply.status()).as(reply.body()).isEqualTo(200);
        java.util.List<String> ids = new java.util.ArrayList<>();
        reply.json().get("items").forEach(item -> ids.add(item.get("ticketId").stringValue()));
        return ids;
    }

    public Reply purchase(TestTokenIssuer.Identity identity, String eventId, String... ticketIds) {
        return purchase(identity, eventId, key(), null, ticketIds);
    }

    public Reply purchase(TestTokenIssuer.Identity identity, String eventId, String key, Consumer<HttpHeaders> extra,
            String... ticketIds) {
        StringBuilder tickets = new StringBuilder();
        for (String ticketId : ticketIds) {
            tickets.append(tickets.isEmpty() ? "" : ",").append('"').append(ticketId).append('"');
        }
        String body = "{\"eventId\":\"" + eventId + "\",\"ticketIds\":[" + tickets + "]}";
        return exchange(client.post().uri("/api/v1/orders")
                .headers(headers -> {
                    headers.setBearerAuth(tokens.token(identity));
                    headers.set("Idempotency-Key", key);
                    if (extra != null) {
                        extra.accept(headers);
                    }
                })
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body));
    }

    public Reply order(TestTokenIssuer.Identity identity, String orderId) {
        return exchange(client.get().uri("/api/v1/orders/{id}", orderId)
                .headers(headers -> headers.setBearerAuth(tokens.token(identity))));
    }

    /** Waits until the Order of {@code identity} reaches {@code status}; returns its representation. */
    public JsonNode awaitOrder(TestTokenIssuer.Identity identity, String orderId, String status, Duration timeout) {
        JsonNode[] last = new JsonNode[1];
        await().atMost(timeout).pollInterval(Duration.ofMillis(250)).untilAsserted(() -> {
            Reply reply = order(identity, orderId);
            assertThat(reply.status()).as(reply.body()).isEqualTo(200);
            last[0] = reply.json();
            assertThat(last[0].get("status").stringValue()).isEqualTo(status);
        });
        return last[0];
    }

    public Reply get(String path, String bearer) {
        return exchange(client.get().uri(path).headers(headers -> {
            if (bearer != null) {
                headers.setBearerAuth(bearer);
            }
        }));
    }

    private static Reply exchange(WebTestClient.RequestHeadersSpec<?> request) {
        EntityExchangeResult<byte[]> result = request.exchange().expectBody().returnResult();
        byte[] body = result.getResponseBody();
        return new Reply(result.getStatus().value(), result.getResponseHeaders(),
                body == null ? "" : new String(body, StandardCharsets.UTF_8));
    }
}
