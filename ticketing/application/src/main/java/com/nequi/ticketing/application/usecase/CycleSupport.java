package com.nequi.ticketing.application.usecase;

import com.nequi.ticketing.application.port.in.CycleResult;
import com.nequi.ticketing.application.port.in.ItemOutcome;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Shared mechanics of the periodic cycles (ADR-028 rule 3): one candidate or one index query never
 * fails the whole cycle; a failed candidate or query is counted as {@link ItemOutcome#FAILED} and
 * retried by the next cycle, and the work runs with the concurrency limit of its own process.
 */
final class CycleSupport {

    private CycleSupport() {
    }

    /** Wraps one index query: its candidates, followed by an empty marker if the query fails. */
    static <T> Flux<Optional<T>> guarded(Flux<T> query) {
        return query.map(Optional::of).onErrorResume(error -> Flux.just(Optional.empty()));
    }

    static <T> Flux<ItemOutcome> process(Flux<Optional<T>> candidates, Function<T, Mono<ItemOutcome>> handler,
            int concurrency) {
        return candidates.flatMap(candidate -> candidate
                .map(value -> Mono.defer(() -> handler.apply(value))
                        .defaultIfEmpty(ItemOutcome.NOT_APPLICABLE)
                        .onErrorReturn(ItemOutcome.FAILED))
                .orElseGet(() -> Mono.just(ItemOutcome.FAILED)), concurrency);
    }

    static Mono<CycleResult> summarize(Flux<ItemOutcome> outcomes) {
        return outcomes
                .collect(() -> new EnumMap<ItemOutcome, Long>(ItemOutcome.class),
                        (counts, outcome) -> counts.merge(outcome, 1L, Long::sum))
                .map(counts -> new CycleResult(false, Map.copyOf(counts)));
    }
}
