package com.nequi.ticketing.infrastructure.adapter.in.scheduler;

import java.time.Duration;
import java.util.Objects;

/**
 * Scheduling of one periodic process (ADR-028): its period and the bounded growing wait after a failed cycle
 * (ADR-028 rule 3, IV-004): the first wait after a failure is {@code failureInitialWait}, every further
 * consecutive failure doubles it, and it never exceeds {@code failureMaxWait}. A successful cycle returns to
 * the period.
 */
public record PeriodicProcessSettings(Duration period, Duration failureInitialWait, Duration failureMaxWait) {

    public PeriodicProcessSettings {
        requireAtLeastOneMillisecond(period, "period");
        requireAtLeastOneMillisecond(failureInitialWait, "failureInitialWait");
        requireAtLeastOneMillisecond(failureMaxWait, "failureMaxWait");
        if (failureMaxWait.compareTo(failureInitialWait) < 0) {
            throw new IllegalArgumentException("failureMaxWait must not be shorter than failureInitialWait");
        }
    }

    /** Wait before the next cycle after {@code consecutiveFailures} failed cycles in a row (at least 1). */
    public Duration failureWait(int consecutiveFailures) {
        if (consecutiveFailures < 1) {
            throw new IllegalArgumentException("consecutiveFailures must be positive");
        }
        Duration wait = failureInitialWait;
        for (int failure = 1; failure < consecutiveFailures && wait.compareTo(failureMaxWait) < 0; failure++) {
            wait = wait.multipliedBy(2);
        }
        return wait.compareTo(failureMaxWait) > 0 ? failureMaxWait : wait;
    }

    private static void requireAtLeastOneMillisecond(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.toMillis() < 1) {
            throw new IllegalArgumentException(name + " must be at least 1 ms");
        }
    }
}
