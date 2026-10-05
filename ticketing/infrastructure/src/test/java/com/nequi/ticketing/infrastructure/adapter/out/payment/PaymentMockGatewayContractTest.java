package com.nequi.ticketing.infrastructure.adapter.out.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.nequi.ticketing.application.port.out.AuthorizationOutcome;
import com.nequi.ticketing.application.port.out.CancellationOutcome;
import com.nequi.ticketing.application.port.out.CancellationOutcome.CancellationStatus;
import com.nequi.ticketing.application.port.out.Clock;
import com.nequi.ticketing.application.port.out.PaymentAuthorization;
import com.nequi.ticketing.infrastructure.adapter.out.payment.PaymentMockContractServer.Behaviour;
import com.nequi.ticketing.infrastructure.adapter.out.payment.PaymentMockContractServer.RecordedRequest;
import com.nequi.ticketing.infrastructure.adapter.out.payment.PaymentMockContractServer.Type;
import com.nequi.ticketing.infrastructure.adapter.out.resilience.CircuitBreakerSettings;
import com.nequi.ticketing.infrastructure.adapter.out.resilience.CircuitState;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;

/**
 * CMP-013 against the reactive test server that implements {@code payment-mock.openapi.v1.yaml} (ADR-030,
 * ADR-038, IV-003): every request of the adapter and every response of the double is validated against the
 * versioned copy of the contract. Real HTTP on localhost with real time; the call timeout is shortened where
 * latency is simulated (the approved values are verified with virtual time in {@code PaymentMockGatewayPolicyTest}).
 */
class PaymentMockGatewayContractTest {

    private static final String API_KEY = "test-api-key";
    private static final String EVENT_ID = "0f8fad5b-d9cb-469f-a165-70867728950e";
    private static final String CUSTOMER = "customer-sub-1";
    private static final Clock CLOCK = Instant::now;

    private final PaymentMockContractServer server = PaymentMockContractServer.start(API_KEY);
    private final RecordingPaymentEvents events = new RecordingPaymentEvents();

    @AfterEach
    void stop() {
        try {
            assertThat(server.contractViolations())
                    .as(PaymentMockContractServer.describe(server.contractViolations()))
                    .isEmpty();
        } finally {
            server.close();
        }
    }

    @Test
    @DisplayName("AC-019 FR-015 AC-024 API-101 approval: Idempotency-Key = paymentAttemptId, X-Api-Key and the contract body")
    void approval() {
        PaymentAuthorization request = request();
        PaymentMockGateway gateway = gateway(PaymentGatewaySettings.deployed(URI.create(server.baseUrl()), API_KEY));

        StepVerifier.create(gateway.authorize(request, Instant.now().plusSeconds(30)))
                .expectNext(new AuthorizationOutcome.Approved("prov-" + request.paymentAttemptId()))
                .verifyComplete();

        RecordedRequest recorded = server.requests(PaymentEvents.AUTHORIZE).getFirst();
        assertThat(recorded.uri()).isEqualTo("/payments");
        assertThat(recorded.apiKey()).isEqualTo(API_KEY);
        assertThat(recorded.idempotencyKey()).isEqualTo(request.paymentAttemptId());
        assertThat(recorded.contentType()).startsWith("application/json");
        JsonNode body = recorded.json();
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("paymentAttemptId", "orderId", "eventId",
                "customerRef", "ticketIds");
        assertThat(body.get("paymentAttemptId").stringValue()).isEqualTo(request.paymentAttemptId());
        assertThat(body.get("orderId").stringValue()).isEqualTo(request.orderId());
        assertThat(body.get("eventId").stringValue()).isEqualTo(EVENT_ID);
        assertThat(body.get("customerRef").stringValue()).isEqualTo(CUSTOMER);
        assertThat(body.get("ticketIds").valueStream().map(JsonNode::stringValue))
                .containsExactly("A-1-1", "A-1-2");
        assertThat(gateway.circuit().state()).isEqualTo(CircuitState.CLOSED);
        assertThat(events.failures()).isEmpty();
    }

    @Test
    @DisplayName("AC-020 ALT-005 API-101 decline: DECLINED with its reasonCode is a decline, called once")
    void decline() {
        PaymentAuthorization request = request();
        server.behaviour(request.orderId(), Behaviour.decline("INSUFFICIENT_FUNDS"));

        StepVerifier.create(deployedGateway().authorize(request, Instant.now().plusSeconds(30)))
                .expectNext(new AuthorizationOutcome.Declined("prov-" + request.paymentAttemptId(), "INSUFFICIENT_FUNDS"))
                .verifyComplete();
        assertThat(server.authorizations(request.paymentAttemptId())).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-021 ERR-008 API-101 4xx of contract or authentication is a definitive contract error, never retried")
    void contractErrors() {
        PaymentMockGateway gateway = deployedGateway();
        for (int status : new int[] {400, 422}) {
            PaymentAuthorization request = request();
            server.behaviour(request.orderId(), Behaviour.definitiveError(status));
            StepVerifier.create(gateway.authorize(request, Instant.now().plusSeconds(30)))
                    .expectNext(new AuthorizationOutcome.ContractError(status))
                    .verifyComplete();
            assertThat(server.authorizations(request.paymentAttemptId())).isEqualTo(1);
        }
        PaymentMockGateway wrongKey = gateway(PaymentGatewaySettings.deployed(URI.create(server.baseUrl()), "wrong-key"));
        StepVerifier.create(wrongKey.authorize(request(), Instant.now().plusSeconds(30)))
                .expectNext(new AuthorizationOutcome.ContractError(401))
                .verifyComplete();
        assertThat(server.requests(PaymentEvents.AUTHORIZE)).hasSize(3);
        assertThat(gateway.circuit().state()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    @DisplayName("ALT-004 ERR-005 AC-024 FR-017 BR-020 API-101 5xx twice then approved: retried with the same paymentAttemptId and Idempotency-Key")
    void transientThenApproved() {
        PaymentAuthorization request = request();
        server.behaviour(request.orderId(), Behaviour.transientThen(2, 503, "APPROVED"));

        StepVerifier.create(fastGateway().authorize(request, Instant.now().plusSeconds(30)))
                .expectNext(new AuthorizationOutcome.Approved("prov-" + request.paymentAttemptId()))
                .verifyComplete();

        List<RecordedRequest> calls = server.requests(PaymentEvents.AUTHORIZE);
        assertThat(calls).hasSize(3).allSatisfy(call -> {
            assertThat(call.idempotencyKey()).isEqualTo(request.paymentAttemptId());
            assertThat(call.json().get("paymentAttemptId").stringValue()).isEqualTo(request.paymentAttemptId());
        });
        assertThat(events.failures()).containsExactly("authorize HTTP_503", "authorize HTTP_503");
    }

    @Test
    @DisplayName("ALT-004 ERR-005 API-101 persistent 5xx: three calls, then dependency unavailable")
    void persistentServerErrors() {
        PaymentAuthorization request = request();
        server.behaviour(request.orderId(), Behaviour.transientThen(10, 500, "APPROVED"));

        StepVerifier.create(fastGateway().authorize(request, Instant.now().plusSeconds(30)))
                .expectNext(new AuthorizationOutcome.DependencyUnavailable("HTTP_500"))
                .verifyComplete();
        assertThat(server.authorizations(request.paymentAttemptId())).isEqualTo(3);
        assertThat(events.unavailable()).containsExactly("authorize HTTP_500");
    }

    @Test
    @DisplayName("ALT-004 ADR-035 API-101 latency beyond the call timeout: each call times out, three calls, then dependency unavailable")
    void latencyBeyondTimeout() {
        PaymentAuthorization request = request();
        server.behaviour(request.orderId(), Behaviour.latency(Duration.ofSeconds(2)));

        StepVerifier.create(fastGateway().authorize(request, Instant.now().plusSeconds(30)))
                .expectNext(new AuthorizationOutcome.DependencyUnavailable("TIMEOUT"))
                .verifyComplete();
        assertThat(server.requests(PaymentEvents.AUTHORIZE)).hasSize(3);
    }

    @Test
    @DisplayName("ADR-008 API-101 the wait never goes beyond the deadline (expiresAt minus the application margin)")
    void deadline() {
        PaymentAuthorization request = request();
        server.behaviour(request.orderId(), Behaviour.of(Type.NO_RESPONSE));
        long start = System.nanoTime();

        StepVerifier.create(deployedGateway().authorize(request, Instant.now().plusMillis(400)))
                .expectNext(new AuthorizationOutcome.DependencyUnavailable("DEADLINE_REACHED"))
                .verifyComplete();

        assertThat(Duration.ofNanos(System.nanoTime() - start).toMillis()).isLessThan(2_500L);
        assertThat(server.requests(PaymentEvents.AUTHORIZE)).hasSize(1);
    }

    @Test
    @DisplayName("ALT-004 API-101 connection refused, connection closed without response and an invalid 200 body are dependency unavailable")
    void connectionFailuresAndInvalidResponses() {
        DisposableServer closed = HttpServer.create().host("127.0.0.1").port(0).bindNow();
        String url = "http://127.0.0.1:" + closed.port();
        closed.disposeNow();
        StepVerifier.create(gateway(fast(url)).authorize(request(), Instant.now().plusSeconds(30)))
                .expectNext(new AuthorizationOutcome.DependencyUnavailable("CONNECTION_FAILURE"))
                .verifyComplete();

        PaymentAuthorization closing = request();
        server.behaviour(closing.orderId(), Behaviour.of(Type.CLOSE_CONNECTION));
        StepVerifier.create(fastGateway().authorize(closing, Instant.now().plusSeconds(30)))
                .expectNext(new AuthorizationOutcome.DependencyUnavailable("CONNECTION_FAILURE"))
                .verifyComplete();
        assertThat(server.authorizations(closing.paymentAttemptId())).isEqualTo(3);

        PaymentAuthorization invalid = request();
        server.behaviour(invalid.orderId(), Behaviour.of(Type.INVALID_BODY));
        StepVerifier.create(fastGateway().authorize(invalid, Instant.now().plusSeconds(30)))
                .expectNext(new AuthorizationOutcome.DependencyUnavailable("INVALID_RESPONSE"))
                .verifyComplete();
    }

    @Test
    @DisplayName("FR-015 FR-023 API-102 cancellation in the three states: REVERSED, VOIDED and REGISTERED_BEFORE_CHARGE, idempotent")
    void cancellationStates() {
        PaymentMockGateway gateway = deployedGateway();
        PaymentAuthorization approved = request();
        PaymentAuthorization declined = request();
        server.behaviour(declined.orderId(), Behaviour.decline("CARD_DECLINED"));
        gateway.authorize(approved, Instant.now().plusSeconds(30)).then(gateway.authorize(declined,
                Instant.now().plusSeconds(30))).as(StepVerifier::create).expectNextCount(1).verifyComplete();

        StepVerifier.create(gateway.cancel(approved.paymentAttemptId()))
                .expectNext(new CancellationOutcome.Cancelled(CancellationStatus.REVERSED))
                .verifyComplete();
        StepVerifier.create(gateway.cancel(declined.paymentAttemptId()))
                .expectNext(new CancellationOutcome.Cancelled(CancellationStatus.VOIDED))
                .verifyComplete();
        String unknown = UUID.randomUUID() + "-1";
        StepVerifier.create(gateway.cancel(unknown))
                .expectNext(new CancellationOutcome.Cancelled(CancellationStatus.REGISTERED_BEFORE_CHARGE))
                .verifyComplete();
        StepVerifier.create(gateway.cancel(approved.paymentAttemptId()))
                .expectNext(new CancellationOutcome.Cancelled(CancellationStatus.REVERSED))
                .verifyComplete();

        RecordedRequest call = server.requests(PaymentEvents.CANCEL).getFirst();
        assertThat(call.uri()).isEqualTo("/payments/" + approved.paymentAttemptId() + "/cancellation");
        assertThat(call.apiKey()).isEqualTo(API_KEY);
        assertThat(server.cancellations(approved.paymentAttemptId())).isEqualTo(2);
    }

    @Test
    @DisplayName("BR-034 ALT-009 AC-035 (ticketing side) ADR-038/mechanism early cancellation: a later authorization is DECLINED with ATTEMPT_CANCELLED; one cancellation per attempt")
    void earlyCancellation() {
        PaymentMockGateway gateway = deployedGateway();
        PaymentAuthorization request = request();

        StepVerifier.create(gateway.cancel(request.paymentAttemptId()))
                .expectNext(new CancellationOutcome.Cancelled(CancellationStatus.REGISTERED_BEFORE_CHARGE))
                .verifyComplete();
        StepVerifier.create(gateway.authorize(request, Instant.now().plusSeconds(30)))
                .expectNext(new AuthorizationOutcome.Declined("prov-" + request.paymentAttemptId(), "ATTEMPT_CANCELLED"))
                .verifyComplete();
        assertThat(server.cancellations(request.paymentAttemptId())).isEqualTo(1);
        assertThat(server.authorizations(request.paymentAttemptId())).isEqualTo(1);
    }

    @Test
    @DisplayName("BR-034 ALT-009 ADR-038/mechanism an authorization in transit cancelled before its result resolves as DECLINED with ATTEMPT_CANCELLED")
    void cancellationOfAnAuthorizationInTransit() {
        PaymentMockGateway gateway = deployedGateway();
        PaymentAuthorization request = request();
        server.behaviour(request.orderId(), Behaviour.latency(Duration.ofMillis(800)));

        Mono<AuthorizationOutcome> inTransit = gateway.authorize(request, Instant.now().plusSeconds(30)).cache();
        inTransit.subscribe();
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5))
                .until(() -> server.authorizations(request.paymentAttemptId()) == 1);
        StepVerifier.create(gateway.cancel(request.paymentAttemptId()))
                .expectNext(new CancellationOutcome.Cancelled(CancellationStatus.REGISTERED_BEFORE_CHARGE))
                .verifyComplete();

        StepVerifier.create(inTransit)
                .expectNext(new AuthorizationOutcome.Declined("prov-" + request.paymentAttemptId(), "ATTEMPT_CANCELLED"))
                .verifyComplete();
    }

    @Test
    @DisplayName("IV-016 FR-023 ADR-025 API-102 a single call: 5xx is dependency unavailable and 401 a contract error, neither retried")
    void cancellationFailures() {
        String unavailable = UUID.randomUUID() + "-1";
        String rejected = UUID.randomUUID() + "-1";
        server.failCancellation(unavailable, 503);
        server.failCancellation(rejected, 401);
        PaymentMockGateway gateway = deployedGateway();

        StepVerifier.create(gateway.cancel(unavailable))
                .expectNext(new CancellationOutcome.DependencyUnavailable("HTTP_503"))
                .verifyComplete();
        StepVerifier.create(gateway.cancel(rejected))
                .expectNext(new CancellationOutcome.ContractError(401))
                .verifyComplete();
        assertThat(server.requests(PaymentEvents.CANCEL)).hasSize(2);
        assertThat(events.failures()).containsExactly("cancel HTTP_503", "cancel CONTRACT_ERROR_401");
        assertThat(events.unavailable()).containsExactly("cancel HTTP_503");
    }

    private PaymentMockGateway deployedGateway() {
        return gateway(PaymentGatewaySettings.deployed(URI.create(server.baseUrl() + "/"), API_KEY));
    }

    private PaymentMockGateway fastGateway() {
        return gateway(fast(server.baseUrl()));
    }

    private static PaymentGatewaySettings fast(String url) {
        return new PaymentGatewaySettings(URI.create(url), API_KEY, Duration.ofMillis(300), 2, Duration.ofMillis(20),
                Duration.ofMillis(50), 0.5, Duration.ofMillis(300), CircuitBreakerSettings.paymentMock());
    }

    private PaymentMockGateway gateway(PaymentGatewaySettings settings) {
        return PaymentMockGateway.create(settings, CLOCK, events);
    }

    private static PaymentAuthorization request() {
        String orderId = UUID.randomUUID().toString();
        return new PaymentAuthorization(orderId + "-1", orderId, EVENT_ID, CUSTOMER, List.of("A-1-1", "A-1-2"));
    }

    /** Records the notifications of the adapter. */
    static final class RecordingPaymentEvents implements PaymentEvents {

        private final List<String> failures = new CopyOnWriteArrayList<>();
        private final List<String> unavailable = new CopyOnWriteArrayList<>();

        @Override
        public void callFailed(String operation, String paymentAttemptId, String reason) {
            failures.add(operation + " " + reason);
        }

        @Override
        public void dependencyUnavailable(String operation, String paymentAttemptId, String reason) {
            unavailable.add(operation + " " + reason);
        }

        List<String> failures() {
            return List.copyOf(failures);
        }

        List<String> unavailable() {
            return List.copyOf(unavailable);
        }
    }
}
