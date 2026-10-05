package com.nequi.ticketing.infrastructure.adapter.out.sqs;

import com.nequi.ticketing.infrastructure.adapter.out.resilience.CircuitBreakerSettings;
import java.time.Duration;
import java.util.Objects;

/**
 * Configurable values of the SQS publication (CMP-011) with the approved defaults of {@link #deployed}:
 * timeout of 500 ms per attempt, 3 attempts and a total budget of 2 s (ADR-026, ADR-035); backoff between
 * attempts of 100 ms base, x2, jitter 50 % (IV-004); and the publication circuit of ADR-035. Queue URLs are
 * environment configuration (physical names per environment, messaging v2 §1).
 */
public record SqsPublisherSettings(
        String ordersQueueUrl,
        String provisioningQueueUrl,
        Duration attemptTimeout,
        int maxAttempts,
        Duration budget,
        Duration backoffBase,
        double backoffJitter,
        CircuitBreakerSettings circuit) {

    public SqsPublisherSettings {
        requireText(ordersQueueUrl, "ordersQueueUrl");
        requireText(provisioningQueueUrl, "provisioningQueueUrl");
        requirePositive(attemptTimeout, "attemptTimeout");
        requirePositive(budget, "budget");
        requirePositive(backoffBase, "backoffBase");
        Objects.requireNonNull(circuit, "circuit");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        if (backoffJitter < 0 || backoffJitter > 1) {
            throw new IllegalArgumentException("backoffJitter must be in [0, 1]");
        }
    }

    public static SqsPublisherSettings deployed(String ordersQueueUrl, String provisioningQueueUrl) {
        return new SqsPublisherSettings(ordersQueueUrl, provisioningQueueUrl, Duration.ofMillis(500), 3,
                Duration.ofSeconds(2), Duration.ofMillis(100), 0.5, CircuitBreakerSettings.sqsPublication());
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
