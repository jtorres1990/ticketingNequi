package com.nequi.ticketing.infrastructure.adapter.out.dynamodb;

import com.nequi.ticketing.application.port.out.EventCatalog;
import com.nequi.ticketing.application.port.out.IdempotencyStore;
import com.nequi.ticketing.application.port.out.OrderLifecycleStore;
import com.nequi.ticketing.application.port.out.OrderReader;
import com.nequi.ticketing.application.port.out.TicketInventory;

/** The five persistence ports of ADR-034 implemented by one store (in-memory double or DynamoDB adapter). */
record PersistencePorts(
        EventCatalog events,
        TicketInventory tickets,
        OrderLifecycleStore lifecycle,
        OrderReader orders,
        IdempotencyStore idempotency) {

    static <T extends EventCatalog & TicketInventory & OrderLifecycleStore & OrderReader & IdempotencyStore>
            PersistencePorts of(T store) {
        return new PersistencePorts(store, store, store, store, store);
    }

    static PersistencePorts of(DynamoDbPersistence persistence) {
        return new PersistencePorts(persistence.eventCatalog(), persistence.ticketInventory(),
                persistence.orderLifecycleStore(), persistence.orderReader(), persistence.idempotencyStore());
    }
}
