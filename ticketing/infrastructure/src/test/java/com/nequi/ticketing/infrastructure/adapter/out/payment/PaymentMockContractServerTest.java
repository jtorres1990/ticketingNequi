package com.nequi.ticketing.infrastructure.adapter.out.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Control of the contract double (ADR-038, IV-003): the server rejects and reports requests that do not
 * match {@code payment-mock.openapi.v1.yaml}, so the absence of violations in the adapter tests is meaningful.
 */
class PaymentMockContractServerTest {

    private static final String ORDER = "6f1c2a8e-3b7d-4c9a-8e21-0d5f7b9a1c34";
    private static final String VALID_BODY = "{\"paymentAttemptId\":\"" + ORDER + "-1\",\"orderId\":\"" + ORDER + "\","
            + "\"eventId\":\"0f8fad5b-d9cb-469f-a165-70867728950e\",\"customerRef\":\"c\",\"ticketIds\":[\"A-1-1\"]}";

    private final PaymentMockContractServer server = PaymentMockContractServer.start("key");
    private final WebClient client = WebClient.builder().baseUrl(server.baseUrl()).build();

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    @DisplayName("IV-003 control: a request without Idempotency-Key is a contract violation")
    void missingIdempotencyKey() {
        assertThat(post(VALID_BODY, "key", null)).isEqualTo(400);
        assertThat(server.contractViolations()).singleElement().asString().contains("Idempotency-Key");
    }

    @Test
    @DisplayName("IV-003 control: an extra body property or a missing field is a contract violation")
    void invalidBody() {
        assertThat(post(VALID_BODY.replace("]}", "],\"amount\":10}"), "key", ORDER + "-1")).isEqualTo(400);
        assertThat(post(VALID_BODY.replace("\"customerRef\":\"c\",", ""), "key", ORDER + "-1")).isEqualTo(400);
        assertThat(server.contractViolations()).hasSize(2);
    }

    @Test
    @DisplayName("IV-003 control: a missing API key is a 401 reported as a violation of the apiKey scheme; a wrong one a 401")
    void apiKey() {
        assertThat(post(VALID_BODY, null, ORDER + "-1")).isEqualTo(401);
        assertThat(post(VALID_BODY, "wrong", ORDER + "-1")).isEqualTo(401);
        assertThat(server.contractViolations()).singleElement().asString().contains("X-Api-Key");
    }

    @Test
    @DisplayName("IV-003 control: Idempotency-Key different from paymentAttemptId is rejected; a valid request is approved")
    void idempotencyKeyMismatchAndValidRequest() {
        assertThat(post(VALID_BODY, "key", "other-1")).isEqualTo(400);
        assertThat(post(VALID_BODY, "key", ORDER + "-1")).isEqualTo(200);
        assertThat(server.contractViolations()).isEmpty();
    }

    private int post(String body, String apiKey, String idempotencyKey) {
        WebClient.RequestBodySpec spec = client.post().uri("/payments").contentType(MediaType.APPLICATION_JSON);
        if (apiKey != null) {
            spec = spec.header("X-Api-Key", apiKey);
        }
        if (idempotencyKey != null) {
            spec = spec.header("Idempotency-Key", idempotencyKey);
        }
        Mono<Integer> status = spec.bodyValue(body).exchangeToMono(response -> response.releaseBody()
                .thenReturn(response.statusCode().value()));
        int[] result = new int[1];
        StepVerifier.create(status).consumeNextWith(value -> result[0] = value).verifyComplete();
        return result[0];
    }
}
