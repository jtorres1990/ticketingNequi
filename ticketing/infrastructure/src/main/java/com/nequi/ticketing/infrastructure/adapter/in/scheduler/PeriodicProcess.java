package com.nequi.ticketing.infrastructure.adapter.in.scheduler;

import com.nequi.ticketing.domain.event.ShardingPolicy;

/**
 * The four periodic processes of the {@code worker} role triggered by CMP-014 (ADR-028), each with the number
 * of index shards it visits in a random order: expiration (CMP-008, {@code RESV#}), enqueue republish sweep
 * (CMP-023, {@code PENDQ#}), payment reversals (CMP-024, {@code REVERSAL#}) and provisioning cleanup (CMP-015,
 * no sharded index).
 */
public enum PeriodicProcess {

    EXPIRATION(ShardingPolicy.RESERVATION_SHARDS),
    REPUBLISH(ShardingPolicy.PENDING_ENQUEUE_SHARDS),
    REVERSAL(ShardingPolicy.REVERSAL_SHARDS),
    PROVISIONING_CLEANUP(0);

    private final int shardCount;

    PeriodicProcess(int shardCount) {
        this.shardCount = shardCount;
    }

    /** Number of index shards visited by one cycle; {@code 0} when the process has no sharded index. */
    public int shardCount() {
        return shardCount;
    }
}
