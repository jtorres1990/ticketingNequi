package com.nequi.ticketing.infrastructure.adapter.out.resilience;

/** Observable state of a circuit breaker (ADR-035); the state is kept per instance. */
public enum CircuitState {
    CLOSED,
    OPEN,
    HALF_OPEN
}
