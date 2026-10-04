package com.nequi.ticketing.application.usecase;

import com.nequi.ticketing.application.port.out.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;

/**
 * ADR-040: per-instance cache of the available count of each Event. A count is reused for the
 * configured age after it completes, and simultaneous requests share a single count in flight. Errors
 * are never cached. Memory only; no counter is persisted.
 */
final class AvailableCountCache {

    private static final int EXPIRED_ENTRY_SWEEP_THRESHOLD = 1_024;

    private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration ttl;

    AvailableCountCache(Clock clock, Duration ttl) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
    }

    Mono<Count> get(String eventId, Supplier<Mono<Long>> loader) {
        return Mono.defer(() -> {
            Instant now = clock.now();
            Entry entry = entries.compute(eventId, (key, current) ->
                    current != null && current.usableAt(now) ? current : new Entry(key, loader));
            if (entries.size() > EXPIRED_ENTRY_SWEEP_THRESHOLD) {
                entries.values().removeIf(candidate -> !candidate.usableAt(now));
            }
            return entry.count;
        });
    }

    int size() {
        return entries.size();
    }

    /** Count of tickets in {@code AVAILABLE} and the instant it was computed ({@code generatedAt}). */
    record Count(long availableCount, Instant generatedAt) {
    }

    private final class Entry {

        private final Mono<Count> count;
        private volatile Instant completedAt;

        private Entry(String eventId, Supplier<Mono<Long>> loader) {
            this.count = Mono.defer(loader)
                    .switchIfEmpty(Mono.error(() -> new IllegalStateException("the available count was not produced")))
                    .map(value -> new Count(value, clock.now()))
                    .doOnNext(value -> completedAt = value.generatedAt())
                    .doOnError(error -> entries.remove(eventId, this))
                    .cache();
        }

        private boolean usableAt(Instant now) {
            Instant completed = completedAt;
            return completed == null || now.isBefore(completed.plus(ttl));
        }
    }
}
