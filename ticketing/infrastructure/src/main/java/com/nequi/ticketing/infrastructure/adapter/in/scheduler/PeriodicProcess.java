package com.nequi.ticketing.infrastructure.adapter.in.scheduler;

import com.nequi.ticketing.domain.event.ShardingPolicy;

/**
 * The four periodic processes of the {@code worker} role triggered by CMP-014 (ADR-028), each visiting the shards
 * of its index in a random order: expiration (CMP-008, {@code RESV#}), enqueue republish sweep (CMP-023,
 * {@code PENDQ#}), payment reversals (CMP-024, {@code REVERSAL#}) and provisioning cleanup (CMP-015, no sharded
 * index). The shard counts are configurable ({@link ShardingPolicy}, IV-015).
 */
public enum PeriodicProcess {

    EXPIRATION,
    REPUBLISH,
    REVERSAL,
    PROVISIONING_CLEANUP;

    /** Number of index shards visited by one cycle with the approved sharding (8 / 8 / 4 / 0). */
    public int shardCount() {
        return shardCount(ShardingPolicy.DEPLOYED);
    }

    /** Number of index shards visited by one cycle; {@code 0} when the process has no sharded index. */
    public int shardCount(ShardingPolicy sharding) {
        java.util.Objects.requireNonNull(sharding, "sharding");
        return switch (this) {
            case EXPIRATION -> sharding.reservationShards();
            case REPUBLISH -> sharding.pendingEnqueueShards();
            case REVERSAL -> sharding.reversalShards();
            case PROVISIONING_CLEANUP -> 0;
        };
    }
}
