package com.nequi.ticketing.infrastructure.adapter.out.dynamodb;

import com.nequi.ticketing.application.port.out.Clock;
import java.util.Objects;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

/**
 * CMP-010 composition: the five persistence ports of ADR-034 (Event catalog, Ticket inventory, Order
 * lifecycle store, Order reader, Idempotency store) and the AP-017 audit read over one table, sharing the
 * client, the settings and the cache of {@code ENABLED} Events. The table itself is created outside the
 * application ({@code infra-init} locally, IaC in AWS; data model §7).
 */
public record DynamoDbPersistence(
        DynamoDbEventCatalog eventCatalog,
        DynamoDbTicketInventory ticketInventory,
        DynamoDbOrderLifecycleStore orderLifecycleStore,
        DynamoDbOrderReader orderReader,
        DynamoDbIdempotencyStore idempotencyStore,
        DynamoDbAuditTrail auditTrail) {

    public DynamoDbPersistence {
        Objects.requireNonNull(eventCatalog, "eventCatalog");
        Objects.requireNonNull(ticketInventory, "ticketInventory");
        Objects.requireNonNull(orderLifecycleStore, "orderLifecycleStore");
        Objects.requireNonNull(orderReader, "orderReader");
        Objects.requireNonNull(idempotencyStore, "idempotencyStore");
        Objects.requireNonNull(auditTrail, "auditTrail");
    }

    public static DynamoDbPersistence create(DynamoDbAsyncClient client, DynamoDbAdapterSettings settings, Clock clock) {
        DynamoDbTable table = new DynamoDbTable(client, settings);
        DynamoDbEventCatalog events = new DynamoDbEventCatalog(table, new EnabledEventCache(settings.enabledEventCacheSize()));
        return new DynamoDbPersistence(
                events,
                new DynamoDbTicketInventory(table, clock),
                new DynamoDbOrderLifecycleStore(table, events),
                new DynamoDbOrderReader(table),
                new DynamoDbIdempotencyStore(table),
                new DynamoDbAuditTrail(table));
    }
}
