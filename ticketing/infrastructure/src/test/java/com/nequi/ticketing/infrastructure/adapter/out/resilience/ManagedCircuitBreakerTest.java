package com.nequi.ticketing.infrastructure.adapter.out.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nequi.ticketing.application.port.out.Clock;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ManagedCircuitBreakerTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-04T10:00:00Z"));
    private final Clock clock = now::get;
    private final ManagedCircuitBreaker circuit = ManagedCircuitBreaker.create("test",
            CircuitBreakerSettings.sqsPublication(), clock, error -> error instanceof IOException);

    @Test
    @DisplayName("ADR-038/mechanism circuit breaker: closed -> open at 50 % of 10 calls -> half-open after 10 s -> closed after 2 probes")
    void fullCycle() {
        List<CircuitState> changes = new CopyOnWriteArrayList<>();
        circuit.onStateChange(changes::add);

        for (int call = 0; call < 5; call++) {
            call(Mono.just("ok"));
            call(Mono.error(new IOException("transient")));
        }
        assertThat(circuit.state()).isEqualTo(CircuitState.OPEN);
        assertThat(circuit.remainingOpenTime()).isEqualTo(Duration.ofSeconds(10));

        now.set(now.get().plusSeconds(4));
        assertThat(circuit.remainingOpenTime()).isEqualTo(Duration.ofSeconds(6));
        StepVerifier.create(Mono.just("x").transform(circuit.operator()))
                .expectErrorSatisfies(error -> assertThat(ManagedCircuitBreaker.isRejection(error)).isTrue())
                .verify();

        now.set(now.get().plusSeconds(6));
        assertThat(circuit.rejectingCalls()).isTrue();
        assertThat(circuit.remainingOpenTime()).isZero();
        now.set(now.get().plusMillis(1));
        assertThat(circuit.rejectingCalls()).isFalse();
        assertThat(circuit.remainingOpenTime()).isZero();
        assertThat(circuit.state()).isEqualTo(CircuitState.OPEN);
        call(Mono.just("probe-1"));
        assertThat(circuit.state()).isEqualTo(CircuitState.HALF_OPEN);
        assertThat(circuit.remainingOpenTime()).isZero();
        call(Mono.just("probe-2"));
        assertThat(circuit.state()).isEqualTo(CircuitState.CLOSED);
        assertThat(changes).containsExactly(CircuitState.OPEN, CircuitState.HALF_OPEN, CircuitState.CLOSED);
    }

    @Test
    @DisplayName("ADR-038/mechanism circuit breaker: a failed probe reopens the circuit for another 10 s")
    void failedProbeReopens() {
        for (int call = 0; call < 10; call++) {
            call(Mono.error(new IOException("transient")));
        }
        now.set(now.get().plusSeconds(10).plusMillis(1));
        call(Mono.error(new IOException("probe")));
        assertThat(circuit.state()).isEqualTo(CircuitState.HALF_OPEN);
        call(Mono.error(new IOException("probe")));
        assertThat(circuit.state()).isEqualTo(CircuitState.OPEN);
        assertThat(circuit.remainingOpenTime()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("ADR-035 below the minimum of 10 calls or below 50 % of failures the circuit stays closed")
    void thresholds() {
        for (int call = 0; call < 9; call++) {
            call(Mono.error(new IOException("transient")));
        }
        assertThat(circuit.state()).isEqualTo(CircuitState.CLOSED);

        ManagedCircuitBreaker fresh = ManagedCircuitBreaker.create("fresh", CircuitBreakerSettings.sqsPublication(),
                clock, error -> error instanceof IOException);
        for (int call = 0; call < 6; call++) {
            call(fresh, Mono.just("ok"));
        }
        for (int call = 0; call < 4; call++) {
            call(fresh, Mono.error(new IOException("transient")));
        }
        assertThat(fresh.state()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    @DisplayName("ADR-035 errors that are not transient do not count as failures and never open the circuit")
    void nonTransientErrorsAreIgnored() {
        for (int call = 0; call < 30; call++) {
            call(Mono.error(new IllegalStateException("non-retryable")));
        }
        assertThat(circuit.state()).isEqualTo(CircuitState.CLOSED);
        assertThat(circuit.remainingOpenTime()).isZero();
        assertThat(circuit.rejectingCalls()).isFalse();
        assertThat(ManagedCircuitBreaker.isRejection(new IOException())).isFalse();
        assertThat(ManagedCircuitBreaker.isRejection(CallNotPermittedException.createCallNotPermittedException(
                io.github.resilience4j.circuitbreaker.CircuitBreaker.ofDefaults("x")))).isTrue();
    }

    @Test
    @DisplayName("ADR-035 the SQS publication values are 20 / 10 / 50 % / 10 s / 2 and invalid values are rejected")
    void settings() {
        assertThat(CircuitBreakerSettings.sqsPublication())
                .isEqualTo(new CircuitBreakerSettings(20, 10, 50f, Duration.ofSeconds(10), 2));
        assertThatThrownBy(() -> new CircuitBreakerSettings(0, 10, 50f, Duration.ofSeconds(1), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CircuitBreakerSettings(20, 0, 50f, Duration.ofSeconds(1), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CircuitBreakerSettings(20, 10, 50f, Duration.ofSeconds(1), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CircuitBreakerSettings(20, 10, 0f, Duration.ofSeconds(1), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CircuitBreakerSettings(20, 10, 101f, Duration.ofSeconds(1), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CircuitBreakerSettings(20, 10, 50f, Duration.ZERO, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CircuitBreakerSettings(20, 10, 50f, Duration.ofSeconds(-1), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CircuitBreakerSettings(20, 10, 50f, null, 1))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ManagedCircuitBreaker.create(null, CircuitBreakerSettings.sqsPublication(), clock, e -> true))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> circuit.onStateChange(null)).isInstanceOf(NullPointerException.class);
    }

    private void call(Mono<String> mono) {
        call(circuit, mono);
    }

    private static void call(ManagedCircuitBreaker target, Mono<String> mono) {
        StepVerifier.create(mono.transform(target.operator()).onErrorResume(error -> Mono.empty()))
                .thenConsumeWhile(value -> true)
                .verifyComplete();
    }
}
