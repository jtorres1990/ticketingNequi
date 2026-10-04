package com.nequi.ticketing.application.port.in;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Summary of one periodic cycle (logs and metrics of CMP-018): how many candidates ended with each
 * {@link ItemOutcome}, and whether the whole cycle was skipped (for example the sweep with the
 * publication circuit open, ADR-026).
 */
public record CycleResult(boolean skipped, Map<ItemOutcome, Long> outcomes) {

    public CycleResult {
        EnumMap<ItemOutcome, Long> copy = new EnumMap<>(ItemOutcome.class);
        Objects.requireNonNull(outcomes, "outcomes").forEach((outcome, count) -> {
            if (count != null && count > 0) {
                copy.put(outcome, count);
            }
        });
        outcomes = Map.copyOf(copy);
    }

    public static CycleResult skippedCycle() {
        return new CycleResult(true, Map.of());
    }

    public long count(ItemOutcome outcome) {
        return outcomes.getOrDefault(outcome, 0L);
    }

    public long total() {
        return outcomes.values().stream().mapToLong(Long::longValue).sum();
    }
}
