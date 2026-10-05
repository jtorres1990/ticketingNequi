package com.nequi.ticketing.infrastructure.adapter.out.dynamodb;

import java.time.Duration;
import java.util.Objects;

/**
 * Configurable values of the DynamoDB adapter with the approved defaults ({@link #deployed(String)}):
 * <ul>
 *   <li>transactional conflict: retry of the whole transaction at most 2 times with jitter (ADR-023,
 *       ADR-035), backoff 25 ms base, 200 ms maximum, 50 % jitter (IV-004);</li>
 *   <li>batch writes: 25 items per request, up to 4 requests in parallel (ADR-024, data model §4, SPK-010);</li>
 *   <li>consistent batch reads: 100 keys per request (data model §4, SPK-011);</li>
 *   <li>unprocessed items or keys of a batch: retried with bounded backoff (ADR-039);</li>
 *   <li>sold-out probe: waves of 4 shards (data model §4, ADR-040);</li>
 *   <li>count: all shards of the Event in parallel, {@code 0} meaning "all" (IV-004);</li>
 *   <li>cache of {@code ENABLED} Events: 10,000 entries without expiry (IV-004).</li>
 * </ul>
 * The values without an approved figure (unprocessed retries and their backoff, batch read parallelism)
 * are implementation guards reported for human review in the INC-005 report.
 */
public record DynamoDbAdapterSettings(
        String tableName,
        int transactionConflictRetries,
        Duration conflictBackoffBase,
        Duration conflictBackoffMaximum,
        double conflictJitter,
        int batchWriteSize,
        int batchWriteParallelism,
        int batchReadSize,
        int batchReadParallelism,
        int unprocessedRetries,
        Duration unprocessedBackoffBase,
        Duration unprocessedBackoffMaximum,
        int probeWaveSize,
        int countConcurrency,
        long enabledEventCacheSize) {

    public DynamoDbAdapterSettings {
        Objects.requireNonNull(tableName, "tableName");
        Objects.requireNonNull(conflictBackoffBase, "conflictBackoffBase");
        Objects.requireNonNull(conflictBackoffMaximum, "conflictBackoffMaximum");
        Objects.requireNonNull(unprocessedBackoffBase, "unprocessedBackoffBase");
        Objects.requireNonNull(unprocessedBackoffMaximum, "unprocessedBackoffMaximum");
        if (tableName.isBlank()) {
            throw new IllegalArgumentException("tableName must not be blank");
        }
        if (transactionConflictRetries < 0 || unprocessedRetries < 0 || countConcurrency < 0) {
            throw new IllegalArgumentException("retries and concurrency cannot be negative");
        }
        if (conflictJitter < 0 || conflictJitter > 1) {
            throw new IllegalArgumentException("conflictJitter must be between 0 and 1");
        }
        if (conflictBackoffBase.isNegative() || conflictBackoffMaximum.compareTo(conflictBackoffBase) < 0
                || unprocessedBackoffBase.isNegative() || unprocessedBackoffMaximum.compareTo(unprocessedBackoffBase) < 0) {
            throw new IllegalArgumentException("backoff maximum must not be lower than its base");
        }
        if (batchWriteSize < 1 || batchWriteSize > 25 || batchReadSize < 1 || batchReadSize > 100) {
            throw new IllegalArgumentException("batch sizes must respect the service limits (25 writes, 100 reads)");
        }
        if (batchWriteParallelism < 1 || batchReadParallelism < 1 || probeWaveSize < 1 || enabledEventCacheSize < 1) {
            throw new IllegalArgumentException("parallelism, wave size and cache size must be positive");
        }
    }

    public static DynamoDbAdapterSettings deployed(String tableName) {
        return new DynamoDbAdapterSettings(tableName, 2, Duration.ofMillis(25), Duration.ofMillis(200), 0.5,
                25, 4, 100, 4, 5, Duration.ofMillis(50), Duration.ofSeconds(1), 4, 0, 10_000);
    }
}
