package com.nequi.ticketing.infrastructure.adapter.out.payment;

import com.nequi.ticketing.infrastructure.adapter.out.resilience.CircuitBreakerSettings;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * Configurable values of the Payment gateway adapter (CMP-013; ADR-030, ADR-035, IV-004):
 * <ul>
 *   <li>{@code baseUrl}: base URL of the Payment Mock ({@code payment-mock.openapi.v1.yaml});</li>
 *   <li>{@code apiKey}: shared {@code X-Api-Key}, provided only by the environment (ADR-032); never logged
 *       nor included in {@link #toString()};</li>
 *   <li>authorization: timeout of 3 s per call, up to 2 retries of transient failures with exponential
 *       backoff of 200 ms base, at most 1 s, and 50 % jitter (IV-004);</li>
 *   <li>cancellation: one call with a timeout of 3 s (the retry is the persisted reversal schedule, ADR-025);</li>
 *   <li>{@code circuit}: the Payment Mock circuit breaker shared by both operations (ADR-035).</li>
 * </ul>
 * The connect and response timeouts of the HTTP client are derived from the call timeouts, so that no
 * additional value is introduced; the per-call Reactor timeout is the authoritative one (SPK-021).
 */
public record PaymentGatewaySettings(
        URI baseUrl,
        String apiKey,
        Duration authorizationTimeout,
        int authorizationRetries,
        Duration retryBackoffBase,
        Duration retryBackoffMax,
        double retryJitter,
        Duration cancellationTimeout,
        CircuitBreakerSettings circuit) {

    public PaymentGatewaySettings {
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(apiKey, "apiKey");
        Objects.requireNonNull(authorizationTimeout, "authorizationTimeout");
        Objects.requireNonNull(retryBackoffBase, "retryBackoffBase");
        Objects.requireNonNull(retryBackoffMax, "retryBackoffMax");
        Objects.requireNonNull(cancellationTimeout, "cancellationTimeout");
        Objects.requireNonNull(circuit, "circuit");
        if (!baseUrl.isAbsolute()) {
            throw new IllegalArgumentException("baseUrl must be absolute");
        }
        if (apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey must not be blank");
        }
        requirePositive(authorizationTimeout, "authorizationTimeout");
        requirePositive(retryBackoffBase, "retryBackoffBase");
        requirePositive(cancellationTimeout, "cancellationTimeout");
        if (retryBackoffMax.compareTo(retryBackoffBase) < 0) {
            throw new IllegalArgumentException("retryBackoffMax must not be lower than retryBackoffBase");
        }
        if (authorizationRetries < 0) {
            throw new IllegalArgumentException("authorizationRetries must not be negative");
        }
        if (retryJitter < 0 || retryJitter > 1) {
            throw new IllegalArgumentException("retryJitter must be in [0, 1]");
        }
    }

    /** Approved values (ADR-035, IV-004) for the given endpoint and API key. */
    public static PaymentGatewaySettings deployed(URI baseUrl, String apiKey) {
        return new PaymentGatewaySettings(baseUrl, apiKey, Duration.ofSeconds(3), 2, Duration.ofMillis(200),
                Duration.ofSeconds(1), 0.5, Duration.ofSeconds(3), CircuitBreakerSettings.paymentMock());
    }

    /** Longest call timeout, used for the connect and response timeouts of the HTTP client. */
    Duration longestCallTimeout() {
        return authorizationTimeout.compareTo(cancellationTimeout) >= 0 ? authorizationTimeout : cancellationTimeout;
    }

    @Override
    public String toString() {
        return "PaymentGatewaySettings[baseUrl=" + baseUrl
                + ", apiKey=***"
                + ", authorizationTimeout=" + authorizationTimeout
                + ", authorizationRetries=" + authorizationRetries
                + ", retryBackoffBase=" + retryBackoffBase
                + ", retryBackoffMax=" + retryBackoffMax
                + ", retryJitter=" + retryJitter
                + ", cancellationTimeout=" + cancellationTimeout
                + ", circuit=" + circuit + "]";
    }

    private static void requirePositive(Duration value, String name) {
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
