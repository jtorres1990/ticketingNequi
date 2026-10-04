package com.nequi.ticketing.application.port.out;

import java.time.Duration;
import java.util.Objects;

/**
 * Availability of the queue publication as seen before reserving (ADR-035). It carries no circuit
 * breaker type: {@code Unavailable} reports the remaining time before publication may be retried.
 */
public sealed interface PublisherAvailability
        permits PublisherAvailability.Available, PublisherAvailability.Unavailable {

    static PublisherAvailability available() {
        return new Available();
    }

    static PublisherAvailability unavailable(Duration retryAfter) {
        return new Unavailable(retryAfter);
    }

    record Available() implements PublisherAvailability {
    }

    record Unavailable(Duration retryAfter) implements PublisherAvailability {
        public Unavailable {
            Objects.requireNonNull(retryAfter, "retryAfter");
            if (retryAfter.isNegative()) {
                throw new IllegalArgumentException("retryAfter cannot be negative");
            }
        }
    }
}
