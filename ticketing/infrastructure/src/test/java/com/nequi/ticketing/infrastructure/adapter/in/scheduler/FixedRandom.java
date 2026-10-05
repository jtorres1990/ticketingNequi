package com.nequi.ticketing.infrastructure.adapter.in.scheduler;

import java.util.random.RandomGenerator;

/**
 * Deterministic source of randomness for trigger tests: a fixed phase (capped below the period), the identity
 * shard permutation and correlation ids from a counter.
 */
final class FixedRandom implements RandomGenerator {

    private final long phaseMillis;
    private long counter;

    FixedRandom(long phaseMillis) {
        this.phaseMillis = phaseMillis;
    }

    @Override
    public long nextLong() {
        return ++counter;
    }

    @Override
    public long nextLong(long bound) {
        return Math.min(phaseMillis, bound - 1);
    }

    @Override
    public int nextInt(int bound) {
        return bound - 1;
    }
}
