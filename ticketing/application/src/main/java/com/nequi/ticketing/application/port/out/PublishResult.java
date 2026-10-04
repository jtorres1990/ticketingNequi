package com.nequi.ticketing.application.port.out;

/**
 * Result of a queue publication after the adapter's bounded policy (timeout 500 ms per attempt, 3
 * attempts, 2 s budget, circuit breaker; ADR-026, ADR-035). {@code FAILED} is a definitive failure.
 */
public enum PublishResult {
    PUBLISHED,
    FAILED
}
