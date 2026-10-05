package com.nequi.ticketing.infrastructure.adapter.out.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;

/**
 * Spike SPK-019 of INC-006 ({@code ticketing.architecture.v2.md} §13 item 18): Resilience4j 2.4.0 and its
 * Reactor module with Java 25 and the Reactor version of the Spring Boot 4.1 BOM, with the SQS publication
 * values of ADR-035 and controlled time. It stays in the build as regression evidence; the adapter behaviour
 * is verified by its own tests.
 */
class CircuitBreakerSpikeTest {

    @Test
    @DisplayName("SPK-019 Resilience4j with the Reactor operator: closed -> open -> half-open -> closed with a controlled clock")
    void transitionsAreDeterministic() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-04T10:00:00Z"));
        CircuitBreaker breaker = CircuitBreaker.of("spike", publicationConfig(clock));
        List<String> transitions = new ArrayList<>();
        breaker.getEventPublisher().onStateTransition(event -> transitions.add(event.getStateTransition().name()));

        for (int call = 0; call < 5; call++) {
            run(breaker, Mono.just("ok"));
            run(breaker, Mono.error(new IOException("transient")));
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        StepVerifier.create(Mono.just("never").transformDeferred(CircuitBreakerOperator.of(breaker)))
                .expectError(CallNotPermittedException.class)
                .verify();

        clock.advance(Duration.ofSeconds(9));
        assertThat(breaker.tryAcquirePermission()).isFalse();
        clock.advance(Duration.ofSeconds(1).plusMillis(1));
        run(breaker, Mono.just("probe-1"));
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        run(breaker, Mono.just("probe-2"));
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        for (int call = 0; call < 10; call++) {
            run(breaker, Mono.error(new IOException("transient")));
        }
        clock.advance(Duration.ofSeconds(11));
        run(breaker, Mono.error(new IOException("probe fails")));
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        run(breaker, Mono.error(new IOException("probe fails")));
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        System.out.println("SPK-019 transitions: " + transitions);
        assertThat(transitions).containsExactly(
                "CLOSED_TO_OPEN", "OPEN_TO_HALF_OPEN", "HALF_OPEN_TO_CLOSED", "CLOSED_TO_OPEN", "OPEN_TO_HALF_OPEN",
                "HALF_OPEN_TO_OPEN");
    }

    @Test
    @DisplayName("SPK-019 ignored (non-transient) errors do not open the circuit and a Reactor timeout inside the operator counts as a failure")
    void ignoredErrorsAndTimeouts() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-04T10:00:00Z"));
        CircuitBreaker breaker = CircuitBreaker.of("spike", publicationConfig(clock));
        for (int call = 0; call < 20; call++) {
            run(breaker, Mono.error(new IllegalArgumentException("contract")));
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();

        VirtualTimeScheduler virtual = VirtualTimeScheduler.create();
        for (int call = 0; call < 10; call++) {
            Mono<String> slow = Mono.<String>never().timeout(Duration.ofMillis(500), virtual)
                    .transformDeferred(CircuitBreakerOperator.of(breaker));
            StepVerifier.withVirtualTime(() -> slow, () -> virtual, Long.MAX_VALUE)
                    .thenAwait(Duration.ofMillis(500))
                    .expectError(TimeoutException.class)
                    .verify();
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("SPK-019 NFR-003 concurrent calls through the lock-free sliding window on parallel workers raise no blocking call")
    void concurrentCallsDoNotBlock() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-04T10:00:00Z"));
        CircuitBreaker breaker = CircuitBreaker.of("spike", publicationConfig(clock));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        StepVerifier.create(Flux.range(0, 2_000)
                        .parallel(8)
                        .runOn(Schedulers.parallel())
                        .flatMap(index -> Mono.just(index).transformDeferred(CircuitBreakerOperator.of(breaker))
                                .doOnError(failure::set)
                                .onErrorResume(error -> Mono.empty()))
                        .sequential()
                        .count())
                .expectNext(2_000L)
                .verifyComplete();
        assertThat(failure.get()).isNull();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    private static CircuitBreakerConfig publicationConfig(Clock clock) {
        return CircuitBreakerConfig.custom()
                .slidingWindow(20, 10, CircuitBreakerConfig.SlidingWindowType.COUNT_BASED,
                        CircuitBreakerConfig.SlidingWindowSynchronizationStrategy.LOCK_FREE)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(2)
                .recordException(error -> error instanceof IOException || error instanceof TimeoutException)
                .ignoreException(error -> error instanceof IllegalArgumentException)
                .clock(clock)
                .build();
    }

    private static void run(CircuitBreaker breaker, Mono<String> call) {
        StepVerifier.create(call.transformDeferred(CircuitBreakerOperator.of(breaker))
                        .onErrorResume(error -> Mono.empty())
                        .subscribeOn(Schedulers.parallel()))
                .thenConsumeWhile(value -> true)
                .verifyComplete();
    }

    static final class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
