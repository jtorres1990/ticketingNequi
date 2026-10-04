package com.nequi.ticketing.domain.event;

import static com.nequi.ticketing.domain.shared.DomainChecks.required;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class ShardingPolicy {

    public static final int RESERVATION_SHARDS = 8;
    public static final int REVERSAL_SHARDS = 4;
    public static final int PENDING_ENQUEUE_SHARDS = 8;

    private ShardingPolicy() {
    }

    public static int availabilityShards(int capacity) {
        return Math.min(32, Math.max(1, Math.ceilDiv(capacity, 2_000)));
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
