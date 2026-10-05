package com.nequi.ticketing.domain.event;

import static com.nequi.ticketing.domain.shared.DomainChecks.required;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Sharding of the write-distributed indexes (ADR-022, data model §2 and §4) with configurable counts (plan
 * Annex A, IV-015) and the approved values in {@link #DEPLOYED}: {@code availabilityShards = min(32, max(1,
 * ceil(capacity / 2000)))} per Event (persisted with the Event, so changing the divisor or the maximum only
 * affects new Events), 8 {@code RESV#} shards, 4 {@code REVERSAL#} shards and 8 {@code PENDQ#} shards. The
 * {@code api} and {@code worker} roles must use the same {@code RESV#}, {@code REVERSAL#} and {@code PENDQ#}
 * counts: the shard of an Order is computed when it is written and visited by the periodic processes.
 */
public record ShardingPolicy(
        int availabilityShardDivisor,
        int maximumAvailabilityShards,
        int reservationShards,
        int reversalShards,
        int pendingEnqueueShards) {

    public static final ShardingPolicy DEPLOYED = new ShardingPolicy(2_000, 32, 8, 4, 8);

    public ShardingPolicy {
        if (availabilityShardDivisor < 1 || maximumAvailabilityShards < 1 || reservationShards < 1
                || reversalShards < 1 || pendingEnqueueShards < 1) {
            throw new IllegalArgumentException("shard counts and the availability divisor must be positive");
        }
    }

    /** Availability shards of an Event with the approved divisor (2,000) and maximum (32). */
    public static int availabilityShards(int capacity) {
        return DEPLOYED.availabilityShardsFor(capacity);
    }

    /** Availability shards of an Event of {@code capacity} Tickets with this policy. */
    public int availabilityShardsFor(int capacity) {
        return Math.min(maximumAvailabilityShards, Math.max(1, Math.ceilDiv(capacity, availabilityShardDivisor)));
    }

    /** {@code RESV#} shard of an Order. */
    public int reservationShard(String orderId) {
        return shard(orderId, reservationShards);
    }

    /** {@code REVERSAL#} shard of an Order. */
    public int reversalShard(String orderId) {
        return shard(orderId, reversalShards);
    }

    /** {@code PENDQ#} shard of an Order. */
    public int pendingEnqueueShard(String orderId) {
        return shard(orderId, pendingEnqueueShards);
    }

    public static int shard(String stableIdentity, int shardCount) {
        required(stableIdentity, "stableIdentity");
        if (shardCount < 1) {
            throw new IllegalArgumentException("shardCount must be positive");
        }
        byte[] digest = sha256(stableIdentity);
        int hash = ((digest[0] & 0xff) << 24) | ((digest[1] & 0xff) << 16)
                | ((digest[2] & 0xff) << 8) | (digest[3] & 0xff);
        return Math.floorMod(hash, shardCount);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", impossible);
        }
    }
}
