package com.nequi.ticketing.infrastructure.adapter.out.resilience;

import java.time.Duration;
import java.util.Objects;

/**
 * Configurable values of a count-based circuit breaker (ADR-035): sliding window of the last calls, minimum
 * number of calls before the rates are evaluated, failure-rate threshold in percent, time spent open, number
 * of probe calls permitted while half-open and, optionally, the slow-call criterion (calls longer than
 * {@code slowCallDurationThreshold} whose rate reaches {@code slowCallRateThresholdPercent} also open the
 * circuit). {@code slowCallDurationThreshold == null} means that slow calls do not open the circuit.
 */
public record CircuitBreakerSettings(
        int slidingWindowSize,
        int minimumNumberOfCalls,
        float failureRateThresholdPercent,
        Duration openDuration,
        int halfOpenProbeCalls,
        Duration slowCallDurationThreshold,
        float slowCallRateThresholdPercent) {

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
        if (slowCallDurationThreshold == null) {
            if (slowCallRateThresholdPercent != 0) {
                throw new IllegalArgumentException("a slow-call rate requires a slow-call duration threshold");
            }
        } else {
            if (slowCallDurationThreshold.isNegative() || slowCallDurationThreshold.isZero()) {
                throw new IllegalArgumentException("slowCallDurationThreshold must be positive");
            }
            if (slowCallRateThresholdPercent <= 0 || slowCallRateThresholdPercent > 100) {
                throw new IllegalArgumentException("slow-call rate threshold must be in (0, 100]");
            }
        }
    }

    /** Settings without the slow-call criterion. */
    public CircuitBreakerSettings(int slidingWindowSize, int minimumNumberOfCalls, float failureRateThresholdPercent,
            Duration openDuration, int halfOpenProbeCalls) {
        this(slidingWindowSize, minimumNumberOfCalls, failureRateThresholdPercent, openDuration, halfOpenProbeCalls,
                null, 0f);
    }

    /** Whether slow calls count towards opening the circuit. */
    public boolean slowCallsOpenTheCircuit() {
        return slowCallDurationThreshold != null;
    }

    /** SQS publication circuit (ADR-035): last 20 calls, minimum 10, 50 % of failures, 10 s open, 2 probes. */
    public static CircuitBreakerSettings sqsPublication() {
        return new CircuitBreakerSettings(20, 10, 50f, Duration.ofSeconds(10), 2);
    }

    /**
     * Payment Mock circuit, shared by authorization and cancellation (ADR-035): last 20 calls, minimum 10,
     * 50 % of failures or 50 % of calls longer than 2 s, 15 s open, 3 probe calls.
     */
    public static CircuitBreakerSettings paymentMock() {
        return new CircuitBreakerSettings(20, 10, 50f, Duration.ofSeconds(15), 3, Duration.ofSeconds(2), 50f);
    }
}
