package com.nequi.ticketing.application.port.in;

import java.util.List;
import java.util.Objects;

/**
 * One cycle of a periodic {@code worker} process (ADR-028). {@code correlationId} identifies the cycle
 * (MSG-001 {@code correlationId} of the sweep, audit correlation). {@code shardOrder} is the order in
 * which the index shards are visited; the scheduler supplies a random order with jitter (ADR-028), and
 * an empty list visits every shard in natural order. Processes without shards ignore it.
 */
public record CycleRequest(String correlationId, List<Integer> shardOrder) {

    public CycleRequest {
        Objects.requireNonNull(correlationId, "correlationId");
        shardOrder = List.copyOf(Objects.requireNonNull(shardOrder, "shardOrder"));
    }

    public static CycleRequest of(String correlationId) {
        return new CycleRequest(correlationId, List.of());
    }

    /** The shards to visit for an index with {@code shardCount} shards; invalid shards are ignored. */
    public List<Integer> shards(int shardCount) {
        if (shardOrder.isEmpty()) {
            return java.util.stream.IntStream.range(0, shardCount).boxed().toList();
        }
        return shardOrder.stream().filter(shard -> shard >= 0 && shard < shardCount).distinct().toList();
    }
}
