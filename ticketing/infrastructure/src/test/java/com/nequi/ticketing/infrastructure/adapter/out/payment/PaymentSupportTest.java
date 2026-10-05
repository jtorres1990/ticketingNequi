package com.nequi.ticketing.infrastructure.adapter.out.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nequi.ticketing.application.port.out.AuthorizationOutcome;
import com.nequi.ticketing.application.port.out.CancellationOutcome;
import com.nequi.ticketing.application.port.out.CancellationOutcome.CancellationStatus;
import com.nequi.ticketing.application.port.out.PaymentAuthorization;
import com.nequi.ticketing.infrastructure.adapter.out.payment.PaymentTransport.HttpReply;
import com.nequi.ticketing.infrastructure.adapter.out.resilience.CircuitBreakerSettings;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Wire format, classification and settings of the payment adapter (ADR-030, ADR-035, ADR-034 rule 7). */
class PaymentSupportTest {

    private static final String ATTEMPT = "6f1c2a8e-3b7d-4c9a-8e21-0d5f7b9a1c34-1";

    @Test
    @DisplayName("TC-017 ADR-030 the authorization request carries exactly the contract fields")
    void authorizationRequest() {
        String json = PaymentWireFormat.authorizationRequest(new PaymentAuthorization(ATTEMPT,
                "6f1c2a8e-3b7d-4c9a-8e21-0d5f7b9a1c34", "0f8fad5b-d9cb-469f-a165-70867728950e", "sub-1",
                List.of("A-1-1", "B-2-3")));

        assertThat(json).isEqualTo("{\"paymentAttemptId\":\"" + ATTEMPT + "\","
                + "\"orderId\":\"6f1c2a8e-3b7d-4c9a-8e21-0d5f7b9a1c34\","
                + "\"eventId\":\"0f8fad5b-d9cb-469f-a165-70867728950e\",\"customerRef\":\"sub-1\","
                + "\"ticketIds\":[\"A-1-1\",\"B-2-3\"]}");
    }

    @Test
    @DisplayName("AC-019 AC-020 BR-034 authorization results: APPROVED, DECLINED with and without reasonCode; unknown fields ignored")
    void authorizationResults() {
        assertThat(PaymentWireFormat.authorizationOutcome(ATTEMPT, ok("\"status\":\"APPROVED\",\"providerReference\":\"r\","
                + "\"replayed\":true,\"cancelled\":false,\"extra\":1")))
                .isEqualTo(new AuthorizationOutcome.Approved("r"));
        assertThat(PaymentWireFormat.authorizationOutcome(ATTEMPT, ok("\"status\":\"DECLINED\",\"providerReference\":\"r\","
                + "\"reasonCode\":\"ATTEMPT_CANCELLED\"")))
                .isEqualTo(new AuthorizationOutcome.Declined("r", "ATTEMPT_CANCELLED"));
        assertThat(PaymentWireFormat.authorizationOutcome(ATTEMPT, ok("\"status\":\"DECLINED\",\"providerReference\":\"r\","
                + "\"reasonCode\":null")))
                .isEqualTo(new AuthorizationOutcome.Declined("r", null));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not json",
            "[]",
            "",
            "{\"paymentAttemptId\":\"" + ATTEMPT + "\",\"status\":\"APPROVED\"}",
            "{\"paymentAttemptId\":\"" + ATTEMPT + "\",\"status\":\"APPROVED\",\"providerReference\":\" \"}",
            "{\"paymentAttemptId\":\"" + ATTEMPT + "\",\"status\":\"APPROVED\",\"providerReference\":7}",
            "{\"paymentAttemptId\":\"other-1\",\"status\":\"APPROVED\",\"providerReference\":\"r\"}",
            "{\"status\":\"APPROVED\",\"providerReference\":\"r\"}",
            "{\"paymentAttemptId\":\"" + ATTEMPT + "\",\"providerReference\":\"r\"}",
            "{\"paymentAttemptId\":\"" + ATTEMPT + "\",\"status\":\"PENDING\",\"providerReference\":\"r\"}"
    })
    @DisplayName("ALT-004 a 2xx authorization body that does not match the contract is transient (unknown result)")
    void invalidAuthorizationBodies(String body) {
        assertThatThrownBy(() -> PaymentWireFormat.authorizationOutcome(ATTEMPT, new HttpReply(200, body)))
                .isInstanceOfSatisfying(PaymentCallFailure.Transient.class,
                        failure -> assertThat(failure.reason()).isEqualTo("INVALID_RESPONSE"));
    }

    @Test
    @DisplayName("ADR-030 status classification: 4xx contract error, 5xx and other statuses transient")
    void statusClassification() {
        for (int status : new int[] {400, 401, 404, 409, 422, 499}) {
            assertThatThrownBy(() -> PaymentWireFormat.authorizationOutcome(ATTEMPT, new HttpReply(status, "")))
                    .isInstanceOfSatisfying(PaymentCallFailure.Contract.class, failure -> {
                        assertThat(failure.status()).isEqualTo(status);
                        assertThat(failure.reason()).isEqualTo("CONTRACT_ERROR_" + status);
                    });
        }
        for (int status : new int[] {100, 302, 500, 502, 503, 504}) {
            assertThatThrownBy(() -> PaymentWireFormat.cancellationOutcome(ATTEMPT, new HttpReply(status, "")))
                    .isInstanceOfSatisfying(PaymentCallFailure.Transient.class,
                            failure -> assertThat(failure.reason()).isEqualTo("HTTP_" + status));
        }
        assertThat(PaymentWireFormat.authorizationOutcome(ATTEMPT, new HttpReply(201,
                "{\"paymentAttemptId\":\"" + ATTEMPT + "\",\"status\":\"APPROVED\",\"providerReference\":\"r\"}")))
                .isEqualTo(new AuthorizationOutcome.Approved("r"));
    }

    @Test
    @DisplayName("FR-015 cancellation results REVERSED, VOIDED, REGISTERED_BEFORE_CHARGE; invalid bodies are transient")
    void cancellationResults() {
        for (CancellationStatus status : CancellationStatus.values()) {
            assertThat(PaymentWireFormat.cancellationOutcome(ATTEMPT, ok("\"cancellationStatus\":\"" + status + "\"")))
                    .isEqualTo(new CancellationOutcome.Cancelled(status));
        }
        for (String body : List.of("{\"paymentAttemptId\":\"" + ATTEMPT + "\",\"cancellationStatus\":\"LOST\"}",
                "{\"paymentAttemptId\":\"" + ATTEMPT + "\"}",
                "{\"paymentAttemptId\":\"x-1\",\"cancellationStatus\":\"VOIDED\"}")) {
            assertThatThrownBy(() -> PaymentWireFormat.cancellationOutcome(ATTEMPT, new HttpReply(200, body)))
                    .isInstanceOf(PaymentCallFailure.Transient.class);
        }
        assertThatThrownBy(() -> PaymentWireFormat.cancellationOutcome(ATTEMPT, new HttpReply(200, null)))
                .isInstanceOf(PaymentCallFailure.Transient.class);
    }

    @Test
    @DisplayName("ADR-035 only transient failures count for the circuit; transport errors are classified with stable reasons")
    void failureClassification() {
        assertThat(PaymentCallFailure.classify(new TimeoutException()).reason()).isEqualTo("TIMEOUT");
        assertThat(PaymentCallFailure.classify(new IOException("reset")).reason()).isEqualTo("CONNECTION_FAILURE");
        assertThat(PaymentCallFailure.classify(new IllegalStateException()).reason()).isEqualTo("UNEXPECTED_ERROR");
        PaymentCallFailure contract = PaymentCallFailure.contract(422);
        assertThat(PaymentCallFailure.classify(contract)).isSameAs(contract);
        assertThat(PaymentCallFailure.countsAsFailure(contract)).isFalse();
        assertThat(PaymentCallFailure.countsAsFailure(PaymentCallFailure.transientFailure("HTTP_503"))).isTrue();
        assertThat(PaymentCallFailure.countsAsFailure(new TimeoutException())).isFalse();
        assertThat(contract.getStackTrace()).isEmpty();
    }

    @Test
    @DisplayName("ADR-035 IV-004 ADR-032 approved settings; the API key never appears in toString")
    void settings() {
        PaymentGatewaySettings settings = PaymentGatewaySettings.deployed(URI.create("http://payment-mock:8090"),
                "super-secret-key");

        assertThat(settings.authorizationTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(settings.authorizationRetries()).isEqualTo(2);
        assertThat(settings.retryBackoffBase()).isEqualTo(Duration.ofMillis(200));
        assertThat(settings.retryBackoffMax()).isEqualTo(Duration.ofSeconds(1));
        assertThat(settings.retryJitter()).isEqualTo(0.5);
        assertThat(settings.cancellationTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(settings.circuit()).isEqualTo(CircuitBreakerSettings.paymentMock());
        assertThat(settings.longestCallTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(settings.toString()).doesNotContain("super-secret-key").contains("apiKey=***");
        assertThat(new PaymentGatewaySettings(URI.create("http://h"), "k", Duration.ofSeconds(1), 0, Duration.ofMillis(1),
                Duration.ofMillis(1), 0, Duration.ofSeconds(2), CircuitBreakerSettings.paymentMock())
                .longestCallTimeout()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("ADR-035 invalid settings are rejected")
    void invalidSettings() {
        URI url = URI.create("http://h");
        CircuitBreakerSettings circuit = CircuitBreakerSettings.paymentMock();
        Duration second = Duration.ofSeconds(1);
        assertThatThrownBy(() -> new PaymentGatewaySettings(URI.create("relative"), "k", second, 2, second, second, 0.5,
                second, circuit)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentGatewaySettings(url, " ", second, 2, second, second, 0.5, second, circuit))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentGatewaySettings(url, "k", Duration.ZERO, 2, second, second, 0.5, second,
                circuit)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentGatewaySettings(url, "k", second, -1, second, second, 0.5, second, circuit))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentGatewaySettings(url, "k", second, 2, second, Duration.ofMillis(1), 0.5,
                second, circuit)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentGatewaySettings(url, "k", second, 2, second, second, 1.5, second, circuit))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentGatewaySettings(url, "k", second, 2, second, second, -0.1, second, circuit))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentGatewaySettings(url, "k", second, 2, Duration.ZERO, second, 0.5, second,
                circuit)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentGatewaySettings(url, "k", second, 2, second, second, 0.5,
                Duration.ofMillis(-1), circuit)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ADR-037 the default observation hook does nothing")
    void defaultEvents() {
        PaymentEvents.NONE.callFailed(PaymentEvents.AUTHORIZE, ATTEMPT, "TIMEOUT");
        PaymentEvents.NONE.dependencyUnavailable(PaymentEvents.CANCEL, ATTEMPT, "CIRCUIT_OPEN");

        assertThat(PaymentEvents.AUTHORIZE).isEqualTo("authorize");
        assertThat(PaymentEvents.CANCEL).isEqualTo("cancel");
    }

    private static HttpReply ok(String fields) {
        return new HttpReply(200, "{\"paymentAttemptId\":\"" + ATTEMPT + "\"," + fields + "}");
    }
}
