package com.nequi.ticketing.infrastructure.adapter.out.dynamodb;

import com.nequi.ticketing.domain.audit.AuditRecord;
import com.nequi.ticketing.domain.event.ShardingPolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Key and index values of {@code ticketing.data-model.v2.md} §2.2 and §3. Instants inside sort keys use a
 * fixed-width UTC format so that lexicographic order equals time order; epoch milliseconds use 13 digits.
 * Shards come from the stable hash of the domain ({@link ShardingPolicy}, ADR-022, ADR-034).
 */
final class Keys {

    static final String PK = "PK";
    static final String SK = "SK";
    static final String META = "#META";

    static final String EVENTS_PROVISIONING = "EVENTS#PROVISIONING";
    static final String EVENTS_ENABLED = "EVENTS#ENABLED";
    static final String EVENTS_FAILED = "EVENTS#FAILED";
    static final String REVERSAL_EXHAUSTED = "REVERSAL#EXHAUSTED";
    static final String REVIEW_QUARANTINE = "REVIEW#QUARANTINE";
    static final String AUDIT_PREFIX = "AUDIT#";

    private static final DateTimeFormatter SORTABLE = DateTimeFormatter
            .ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSSSS'Z'")
            .withZone(ZoneOffset.UTC);

    private Keys() {
    }

    // ------------------------------------------------------------------ table keys

    static String event(String eventId) {
        return "EVENT#" + eventId;
    }

    static String ticket(String eventId, String ticketId) {
        return "TICKET#" + eventId + "#" + ticketId;
    }

    static String order(String orderId) {
        return "ORDER#" + orderId;
    }

    static String purchaseIdempotency(String customerId, String idempotencyKey) {
        return "IDEM#" + customerId + "#" + idempotencyKey;
    }

    static String eventCreationIdempotency(String adminSubject, String idempotencyKey) {
        return "IDEMEVT#" + adminSubject + "#" + idempotencyKey;
    }

    static String activeOrder(String customerId, String eventId) {
        return "ACTIVE#" + customerId + "#" + eventId;
    }

    /** Key of a {@code #META} item. */
    static Map<String, AttributeValue> meta(String partitionKey) {
        return Map.of(PK, AttributeValue.fromS(partitionKey), SK, AttributeValue.fromS(META));
    }

    /** Audit partition: the Order collection, or the Event collection for Event records (ADR-031). */
    static String auditPartition(AuditRecord audit) {
        return audit.orderId() != null ? order(audit.orderId()) : event(audit.eventId());
    }

    /**
     * {@code AUDIT#<occurredAt>#<code>#<suffix>}. The suffix is derived from the record content, so the
     * same record always has the same key and the "does not exist" condition rejects a duplicate insert.
     */
    static String auditSort(AuditRecord audit) {
        return AUDIT_PREFIX + sortable(audit.occurredAt()) + "#" + audit.code().name() + "#" + contentSuffix(audit);
    }

    // ------------------------------------------------------------------ index values

    static String eventLifecycleSort(Instant instant, String eventId) {
        return sortable(instant) + "#" + eventId;
    }

    static String availability(String eventId, int shard) {
        return "AVAIL#" + eventId + "#" + shard;
    }

    static int availabilityShard(String ticketId, int availabilityShards) {
        return ShardingPolicy.shard(ticketId, availabilityShards);
    }

    static String availabilitySort(String section, String row, int seat) {
        return section + "#" + row + "#" + "%04d".formatted(seat);
    }

    static String reservations(String orderId, ShardingPolicy sharding) {
        return "RESV#" + sharding.reservationShard(orderId);
    }

    static String reservationsShard(int shard) {
        return "RESV#" + shard;
    }

    static String reversals(String orderId, ShardingPolicy sharding) {
        return "REVERSAL#" + sharding.reversalShard(orderId);
    }

    static String reversalsShard(int shard) {
        return "REVERSAL#" + shard;
    }

    static String pendingEnqueue(String orderId, ShardingPolicy sharding) {
        return "PENDQ#" + sharding.pendingEnqueueShard(orderId);
    }

    static String pendingEnqueueShard(int shard) {
        return "PENDQ#" + shard;
    }

    /** {@code <epoch milliseconds, 13 digits>#<id>}. */
    static String millisSort(Instant instant, String id) {
        return millis13(instant.toEpochMilli()) + "#" + id;
    }

    static String instantSort(Instant instant, String id) {
        return sortable(instant) + "#" + id;
    }

    static String millis13(long epochMillis) {
        if (epochMillis < 0) {
            throw new IllegalArgumentException("instants before the epoch are not supported in sort keys");
        }
        return "%013d".formatted(epochMillis);
    }

    static String sortable(Instant instant) {
        return SORTABLE.format(Objects.requireNonNull(instant, "instant"));
    }

    static Instant parseSortable(String value) {
        return SORTABLE.parse(value, Instant::from);
    }

    private static String contentSuffix(AuditRecord audit) {
        String canonical = String.join("|",
                audit.code().name(), String.join(",", audit.transitionIds()), String.valueOf(audit.eventId()),
                String.valueOf(audit.orderId()), String.valueOf(audit.orderFrom()), String.valueOf(audit.orderTo()),
                String.valueOf(audit.ticketFrom()), String.valueOf(audit.ticketTo()), String.join(",", audit.ticketIds()),
                String.valueOf(audit.cause()), audit.actor().type().name(), audit.actor().id(), audit.correlationId(),
                String.valueOf(audit.paymentAttemptId()), audit.occurredAt().toString(),
                audit.includedCodes().toString(), String.valueOf(audit.capacity()),
                String.valueOf(audit.availableCount()), String.valueOf(audit.complimentaryCount()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", impossible);
        }
    }
}
