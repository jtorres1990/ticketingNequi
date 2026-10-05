package com.nequi.ticketing.infrastructure.adapter.out.resilience;

import com.nequi.ticketing.application.port.out.Clock;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import reactor.core.publisher.Mono;

/**
 * CMP-026 circuit breaker of one outbound dependency (ADR-035), built on Resilience4j and its Reactor module
 * (verified in SPK-019). Count-based sliding window with the lock-free synchronization strategy, so that
 * recording a call never parks a reactive thread (NFR-003). Only the errors classified as transient by the
 * adapter count as failures; every other error is ignored by the statistics. The clock is the application
 * clock, which makes the transitions deterministic under controlled time.
 *
 * <p>Besides the Reactor operator it exposes the state, the remaining open time (for {@code Retry-After},
 * OpenAPI v2) and state-change notifications (pause and resume of consumers, metrics in INC-010).
 */
public final class ManagedCircuitBreaker {

    private final CircuitBreaker breaker;
    private final Clock clock;
    private final Duration openDuration;
    private final int probeCalls;
    private final List<Consumer<CircuitState>> listeners = new CopyOnWriteArrayList<>();
    private volatile Instant openUntil = Instant.MIN;

    private ManagedCircuitBreaker(CircuitBreaker breaker, Clock clock, Duration openDuration, int probeCalls) {
        this.breaker = breaker;
        this.clock = clock;
        this.openDuration = openDuration;
        this.probeCalls = probeCalls;
        breaker.getEventPublisher()
                .onStateTransition(event -> onTransition(map(event.getStateTransition().getToState())));
    }

    /**
     * @param name      name of the circuit (diagnostics and metrics)
     * @param settings  approved values of the dependency
     * @param clock     application clock
     * @param isFailure classification of the adapter: {@code true} for the errors that count as failures
     */
    public static ManagedCircuitBreaker create(String name, CircuitBreakerSettings settings, Clock clock,
            Predicate<Throwable> isFailure) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(isFailure, "isFailure");
        CircuitBreakerConfig.Builder config = CircuitBreakerConfig.custom()
                .slidingWindow(settings.slidingWindowSize(), settings.minimumNumberOfCalls(),
                        CircuitBreakerConfig.SlidingWindowType.COUNT_BASED,
                        CircuitBreakerConfig.SlidingWindowSynchronizationStrategy.LOCK_FREE)
                .failureRateThreshold(settings.failureRateThresholdPercent())
                .waitDurationInOpenState(settings.openDuration())
                .permittedNumberOfCallsInHalfOpenState(settings.halfOpenProbeCalls())
                .recordException(isFailure)
                .ignoreException(isFailure.negate())
                .clock(new ApplicationClock(clock))
                // Call durations (slow calls) are measured on the application clock as well.
                .currentTimestampFunction(java.time.Clock::millis, TimeUnit.MILLISECONDS);
        if (settings.slowCallsOpenTheCircuit()) {
            config.slowCallDurationThreshold(settings.slowCallDurationThreshold())
                    .slowCallRateThreshold(settings.slowCallRateThresholdPercent());
        }
        return new ManagedCircuitBreaker(CircuitBreaker.of(name, config.build()), clock, settings.openDuration(),
                settings.halfOpenProbeCalls());
    }

    /** Reactor operator: each subscription is one call of the statistics; rejected while the circuit is open. */
    public <T> UnaryOperator<Mono<T>> operator() {
        CircuitBreakerOperator<T> operator = CircuitBreakerOperator.of(breaker);
        return mono -> mono.transformDeferred(operator);
    }

    public CircuitState state() {
        return map(breaker.getState());
    }

    /**
     * Whether the circuit is open and still rejecting calls; once the open duration has passed the next call
     * is admitted as a probe and moves the circuit to half-open.
     */
    public boolean rejectingCalls() {
        return breaker.getState() == CircuitBreaker.State.OPEN && !clock.now().isAfter(openUntil);
    }

    /** Time left before the open circuit admits probe calls; zero when it is not {@link #rejectingCalls()}. */
    public Duration remainingOpenTime() {
        return rejectingCalls() ? Duration.between(clock.now(), openUntil) : Duration.ZERO;
    }

    /** Number of probe calls the circuit permits while half-open (ADR-035). */
    public int probeCalls() {
        return probeCalls;
    }

    /** Registers a listener of state changes; it runs on the thread that caused the transition and must not block. */
    public void onStateChange(Consumer<CircuitState> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /** Whether an error is the rejection of a call because the circuit is open (or its probe calls are taken). */
    public static boolean isRejection(Throwable error) {
        return error instanceof CallNotPermittedException;
    }

    private void onTransition(CircuitState to) {
        if (to == CircuitState.OPEN) {
            openUntil = clock.now().plus(openDuration);
        }
        listeners.forEach(listener -> listener.accept(to));
    }

    private static CircuitState map(CircuitBreaker.State state) {
        return switch (state) {
            case OPEN, FORCED_OPEN -> CircuitState.OPEN;
            case HALF_OPEN -> CircuitState.HALF_OPEN;
            case CLOSED, DISABLED, METRICS_ONLY -> CircuitState.CLOSED;
        };
    }

    /** {@link java.time.Clock} view of the application clock for Resilience4j. */
    private static final class ApplicationClock extends java.time.Clock {

        private final Clock clock;

        private ApplicationClock(Clock clock) {
            this.clock = clock;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public java.time.Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return clock.now();
        }
    }
}
