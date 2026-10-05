package com.nequi.ticketing.infrastructure.adapter.out.dynamodb;

import static com.nequi.ticketing.infrastructure.adapter.out.dynamodb.Attributes.instant;
import static com.nequi.ticketing.infrastructure.adapter.out.dynamodb.Attributes.n;
import static com.nequi.ticketing.infrastructure.adapter.out.dynamodb.Attributes.s;

import com.nequi.ticketing.application.port.out.IdempotencyRecord;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Idempotency items of {@code ticketing.data-model.v2.md} §3 (ADR-027): {@code IDEM#<customerId>#<key>}
 * with {@code orderId} and {@code IDEMEVT#<adminSubject>#<key>} with {@code eventId}. The validity is the
 * TTL attribute {@code ttl} in epoch seconds, rounded up so that it never shortens the minimum 24 h; the
 * logic never depends on the deletion (SPK-013).
 */
final class IdempotencyItems {

    static final String PURCHASE_ENTITY = "PURCHASE_IDEMPOTENCY";
    static final String EVENT_CREATION_ENTITY = "EVENT_CREATION_IDEMPOTENCY";
    static final String ENTITY_TYPE = "entityType";
    static final String ORDER_ID = "orderId";
    static final String EVENT_ID = "eventId";
    static final String REQUEST_HASH = "requestHash";
    static final String CREATED_AT = "createdAt";
    static final String TTL = "ttl";

    private IdempotencyItems() {
    }

    static Map<String, AttributeValue> purchase(IdempotencyRecord record) {
        return item(Keys.purchaseIdempotency(record.ownerId(), record.idempotencyKey()), PURCHASE_ENTITY, ORDER_ID, record);
    }

    static Map<String, AttributeValue> eventCreation(IdempotencyRecord record) {
        return item(Keys.eventCreationIdempotency(record.ownerId(), record.idempotencyKey()), EVENT_CREATION_ENTITY,
                EVENT_ID, record);
    }

    static IdempotencyRecord purchase(String customerId, String idempotencyKey, Map<String, AttributeValue> item) {
        return record(customerId, idempotencyKey, Attributes.string(item, ORDER_ID), item);
    }

    static IdempotencyRecord eventCreation(String adminSubject, String idempotencyKey, Map<String, AttributeValue> item) {
        return record(adminSubject, idempotencyKey, Attributes.string(item, EVENT_ID), item);
    }

    /** Epoch seconds of the expiry, rounded up. */
    static long ttl(Instant expiresAt) {
        return expiresAt.getEpochSecond() + (expiresAt.getNano() > 0 ? 1 : 0);
    }

    private static Map<String, AttributeValue> item(String partitionKey, String entity, String resourceAttribute,
            IdempotencyRecord record) {
        Map<String, AttributeValue> item = new HashMap<>(Keys.meta(partitionKey));
        item.put(ENTITY_TYPE, s(entity));
        item.put(resourceAttribute, s(record.resourceId()));
        item.put(REQUEST_HASH, s(record.requestHash()));
        item.put(CREATED_AT, instant(record.createdAt()));
        item.put(TTL, n(ttl(record.expiresAt())));
        return item;
    }

    private static IdempotencyRecord record(String ownerId, String idempotencyKey, String resourceId,
            Map<String, AttributeValue> item) {
        return new IdempotencyRecord(
                ownerId,
                idempotencyKey,
                resourceId,
                Attributes.string(item, REQUEST_HASH),
                Attributes.instant(item, CREATED_AT),
                Instant.ofEpochSecond(Attributes.number(item, TTL)));
    }
}
