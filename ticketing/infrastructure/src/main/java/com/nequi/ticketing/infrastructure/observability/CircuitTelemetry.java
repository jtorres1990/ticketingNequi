package com.nequi.ticketing.infrastructure.observability;

import com.nequi.ticketing.infrastructure.adapter.out.resilience.CircuitState;
import com.nequi.ticketing.infrastructure.adapter.out.resilience.ManagedCircuitBreaker;
import io.micrometer.core.instrument.Gauge;
import java.util.Objects;

/**
 * CMP-026 metric of the state of each circuit breaker (ADR-035, ADR-037 alarm catalog: "apertura de cada circuit
 * breaker"): one {@code ticketing.circuit.state} gauge per state (1 for the current state, 0 otherwise), a
 * {@code ticketing.circuit.transitions} counter and a WARN record when the circuit opens. The state is per
 * instance (ADR-035).
 */
final class CircuitTelemetry {

    private CircuitTelemetry() {
    }

    static void bind(Telemetry telemetry, String circuit, ManagedCircuitBreaker breaker) {
        Objects.requireNonNull(circuit, "circuit");
        Objects.requireNonNull(breaker, "breaker");
        for (CircuitState state : CircuitState.values()) {
            Gauge.builder(MetricNames.CIRCUIT_STATE, breaker, current -> current.state() == state ? 1 : 0)
                    .tag("circuit", circuit)
                    .tag("state", state.name())
                    .description("1 for the current state of the circuit breaker")
                    .register(telemetry.registry());
        }
        breaker.onStateChange(to -> {
            telemetry.counter(MetricNames.CIRCUIT_TRANSITIONS, "circuit", circuit, "to", to.name()).increment();
            StructuredLog.Entry entry = to == CircuitState.OPEN
                    ? telemetry.log().warn("circuit.opened", "Circuit breaker opened")
                    : telemetry.log().info("circuit.transition", "Circuit breaker changed state");
            entry.with("circuit", circuit).with("state", to).write();
        });
    }
}
