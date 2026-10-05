package com.nequi.ticketing.domain.order;

import static com.nequi.ticketing.domain.shared.DomainChecks.required;

import java.time.Duration;
import java.util.List;

/**
 * Configurable schedule of the payment reversal (plan Annex A, IV-015) with the approved values of ADR-025 /
 * FG-003 in {@link #DEPLOYED}: after each transient cancellation failure the next attempt waits 10 s, 30 s,
 * 1 min, 2 min, 5 min and then 10 min (the last delay repeats); the reversal is exhausted for manual review
 * after {@code maximumAttempts} failed attempts (10).
 */
public record ReversalSchedule(List<Duration> delays, int maximumAttempts) {

    public static final ReversalSchedule DEPLOYED = new ReversalSchedule(List.of(
            Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofMinutes(1),
            Duration.ofMinutes(2), Duration.ofMinutes(5), Duration.ofMinutes(10)), 10);

    public ReversalSchedule {
        delays = List.copyOf(required(delays, "delays"));
        if (delays.isEmpty() || delays.stream().anyMatch(delay -> delay.isNegative() || delay.isZero())) {
            throw new IllegalArgumentException("reversal delays must be positive and not empty");
        }
        if (maximumAttempts < 1) {
            throw new IllegalArgumentException("maximumAttempts must be positive");
        }
    }

    /**
     * Delay after the {@code (failureIndex + 1)}-th transient failure: index 0 is the first failure; indexes
     * beyond the configured delays repeat the last one.
     */
    public Duration delayAfter(int failureIndex) {
        if (failureIndex < 0 || failureIndex >= maximumAttempts) {
            throw new IllegalArgumentException("failureIndex must be between 0 and " + (maximumAttempts - 1));
        }
        return delays.get(Math.min(failureIndex, delays.size() - 1));
    }
}
