package com.nequi.paymentmock.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nequi.paymentmock.support.WebTestSupport;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;

/**
 * LATENCY over HTTP with short real waits (PM-SPK-004, PM-IV-013): the response is delayed without blocking, a caller
 * that gives up does not stop the decision, and a cancellation received during the wait wins (FG-003, ADR-030 rule 3).
 * Assertions use generous tolerances and server-side state, never tight timings.
 */
class LatencyWebTest extends WebTestSupport {

    static final String ORDER = "8a7d2c8e-5f0e-4c39-9d2e-1f4b2a6b7c11";
    static final String EVENT = "3f1c0b7e-2a4d-4e5f-9a8b-7c6d5e4f3a2b";

    static String body(String attemptId) {
        return json("{'paymentAttemptId':'" + attemptId + "','orderId':'" + ORDER + "','eventId':'" + EVENT
                + "','customerRef':'c-1','ticketIds':['T1']}");
    }

    WebClient webClient() {
        return WebClient.builder().baseUrl("http://127.0.0.1:" + port)
                .defaultHeader("X-Api-Key", API_KEY).build();
    }

    CompletableFuture<String> authorizeAsync(String attemptId, Duration callerTimeout) {
        return webClient().post().uri("/payments").header("Idempotency-Key", attemptId)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body(attemptId))
                .retrieve().bodyToMono(String.class)
                .timeout(callerTimeout)
                .toFuture();
    }

    void latencyRule(int ms) {
        post("/control/rules", json("{'match':{'orderId':'" + ORDER + "'},'behaviour':{'type':'LATENCY',"
                + "'addedLatencyMs':" + ms + ",'finalOutcome':'APPROVED'}}"));
    }

    /** Polls API-108 from the test thread until the attempt has a result. */
    JsonNode awaitResult(String attemptId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Response record = get("/control/authorizations/" + attemptId);
            if (record.status() == 200 && record.json().has("result")) {
                return record.json();
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no result for " + attemptId);
    }

    void awaitInvocations(String attemptId, int invocations) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Response record = get("/control/authorizations/" + attemptId);
            if (record.status() == 200 && record.json().get("invocations").intValue() >= invocations) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("invocations not reached for " + attemptId);
    }

    @Test
    void responseIsDelayedByTheAddedLatency() throws Exception {
        latencyRule(300);
        long start = System.nanoTime();
        Response response = send(HttpMethod.POST, "/payments", body("l-1"), headers -> {
            headers.set("X-Api-Key", API_KEY);
            headers.set("Idempotency-Key", "l-1");
        }).assertConforms();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertThat(response.json().get("status").stringValue()).isEqualTo("APPROVED");
        assertThat(elapsedMs).isGreaterThanOrEqualTo(290);
        // The repetition answers from the store, without latency.
        long again = System.nanoTime();
        assertThat(send(HttpMethod.POST, "/payments", body("l-1"), headers -> {
            headers.set("X-Api-Key", API_KEY);
            headers.set("Idempotency-Key", "l-1");
        }).json().get("replayed").booleanValue()).isTrue();
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - again)).isLessThan(290);
    }

    @Test
    void decisionIsCommittedAfterTheCallerGivesUp() throws Exception {
        latencyRule(600);
        CompletableFuture<String> caller = authorizeAsync("l-2", Duration.ofMillis(150));
        assertThatThrownBy(() -> caller.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(java.util.concurrent.TimeoutException.class);
        JsonNode record = awaitResult("l-2");
        assertThat(record.get("invocations").intValue()).isEqualTo(1);
        assertThat(record.get("result").get("status").stringValue()).isEqualTo("APPROVED");
        assertThat(post("/payments/l-2/cancellation", null).json().get("cancellationStatus").stringValue())
                .isEqualTo("REVERSED");
    }

    @Test
    void cancellationDuringTheWaitWinsOverHttp() throws Exception {
        latencyRule(1_500);
        CompletableFuture<String> caller = authorizeAsync("l-3", Duration.ofSeconds(10));
        awaitInvocations("l-3", 1);
        Response cancellation = post("/payments/l-3/cancellation", null).assertConforms();
        assertThat(cancellation.json().get("cancellationStatus").stringValue()).isEqualTo("REGISTERED_BEFORE_CHARGE");
        JsonNode result = JSON.readTree(caller.get(10, TimeUnit.SECONDS));
        assertThat(result.get("status").stringValue()).isEqualTo("DECLINED");
        assertThat(result.get("reasonCode").stringValue()).isEqualTo("ATTEMPT_CANCELLED");
        assertThat(result.get("replayed").booleanValue()).isFalse();
        assertThat(result.get("cancelled").booleanValue()).isFalse();
    }

    @Test
    void repetitionDuringTheWaitJoinsTheDecision() throws Exception {
        latencyRule(800);
        CompletableFuture<String> first = authorizeAsync("l-4", Duration.ofSeconds(10));
        awaitInvocations("l-4", 1);
        CompletableFuture<String> second = authorizeAsync("l-4", Duration.ofSeconds(10));
        JsonNode a = JSON.readTree(first.get(10, TimeUnit.SECONDS));
        JsonNode b = JSON.readTree(second.get(10, TimeUnit.SECONDS));
        assertThat(a.get("replayed").booleanValue()).isFalse();
        assertThat(b.get("replayed").booleanValue()).isTrue();
        assertThat(b.get("status")).isEqualTo(a.get("status"));
        assertThat(get("/control/authorizations/l-4").json().get("invocations").intValue()).isEqualTo(2);
    }
}
