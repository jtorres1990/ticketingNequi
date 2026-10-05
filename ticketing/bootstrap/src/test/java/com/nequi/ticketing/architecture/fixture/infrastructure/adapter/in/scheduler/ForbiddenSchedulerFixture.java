package com.nequi.ticketing.architecture.fixture.infrastructure.adapter.in.scheduler;

import com.nequi.ticketing.infrastructure.adapter.out.resilience.CircuitState;
import com.nequi.ticketing.infrastructure.adapter.out.resilience.ManagedCircuitBreaker;

/** Control fixture: a scheduler type that pauses on the circuit breaker itself instead of the consumption gate. */
public final class ForbiddenSchedulerFixture {

    private ForbiddenSchedulerFixture() {
    }

    public static boolean paused(ManagedCircuitBreaker circuit) {
        return circuit.state() == CircuitState.OPEN;
    }
}
