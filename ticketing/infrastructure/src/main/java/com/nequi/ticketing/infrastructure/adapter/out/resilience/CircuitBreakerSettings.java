package com.nequi.ticketing.infrastructure.adapter.out.resilience;

import java.time.Duration;
import java.util.Objects;

/**
 * Configurable values of a count-based circuit breaker (ADR-035): sliding window of the last calls, minimum
 * number of calls before the failure rate is evaluated, failure-rate threshold in percent, time spent open
 * and number of probe calls permitted while half-open.
 */
public record CircuitBreakerSettings(
        int slidingWindowSize,
        int minimumNumberOfCalls,
        float failureRateThresholdPercent,
        Duration openDuration,
        int halfOpenProbeCalls) {

    public CircuitBreakerSettings {
        Objects.requireNonNull(openDuration, "openDuration");
        if (slidingWindowSize < 1 || minimumNumberOfCalls < 1 || halfOpenProbeCalls < 1) {
            throw new IllegalArgumentException("window, minimum and probe calls must be positive");
        }
        if (failureRateThresholdPercent <= 0 || failureRateThresholdPercent > 100) {
            throw new IllegalArgumentException("failure-rate threshold must be in (0, 100]");
        }
        if (openDuration.isNegative() || openDuration.isZero()) {
            throw new IllegalArgumentException("openDuration must be positive");
        }
    }

    /** SQS publication circuit (ADR-035): last 20 calls, minimum 10, 50 % of failures, 10 s open, 2 probes. */
    public static CircuitBreakerSettings sqsPublication() {
        return new CircuitBreakerSettings(20, 10, 50f, Duration.ofSeconds(10), 2);
    }
}
