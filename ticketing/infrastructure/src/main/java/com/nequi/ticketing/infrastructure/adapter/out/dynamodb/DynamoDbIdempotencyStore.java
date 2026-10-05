package com.nequi.ticketing.infrastructure.adapter.out.dynamodb;

import com.nequi.ticketing.application.port.out.IdempotencyRecord;
import com.nequi.ticketing.application.port.out.IdempotencyStore;
import java.util.Objects;
import reactor.core.publisher.Mono;

/**
 * CMP-010 {@code IdempotencyStore}: strongly consistent reads of the purchase (AP-009) and Event creation
 * (AP-022) idempotency records (ADR-027). A record still present is honoured even after its validity.
 */
public final class DynamoDbIdempotencyStore implements IdempotencyStore {

    private final DynamoDbTable table;

    DynamoDbIdempotencyStore(DynamoDbTable table) {
        this.table = Objects.requireNonNull(table, "table");
    }

    @Override
    public Mono<IdempotencyRecord> findPurchase(String customerId, String idempotencyKey) {
        return Mono.defer(() -> table.get("AP-009 purchase idempotency",
                        Keys.meta(Keys.purchaseIdempotency(customerId, idempotencyKey)), true)
                .map(item -> IdempotencyItems.purchase(customerId, idempotencyKey, item)));
    }

    @Override
    public Mono<IdempotencyRecord> findEventCreation(String adminSubject, String idempotencyKey) {
        return Mono.defer(() -> table.get("AP-022 Event creation idempotency",
                        Keys.meta(Keys.eventCreationIdempotency(adminSubject, idempotencyKey)), true)
                .map(item -> IdempotencyItems.eventCreation(adminSubject, idempotencyKey, item)));
    }
}
