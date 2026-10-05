package com.nequi.ticketing.bootstrap.config;

import com.nequi.ticketing.application.port.out.EventCatalog;
import com.nequi.ticketing.application.port.out.IdempotencyStore;
import com.nequi.ticketing.application.port.out.OrderLifecycleStore;
import com.nequi.ticketing.application.port.out.OrderQueuePublisher;
import com.nequi.ticketing.application.port.out.OrderReader;
import com.nequi.ticketing.application.port.out.ProvisioningQueuePublisher;
import com.nequi.ticketing.application.port.out.TicketInventory;

/**
 * Outbound ports of ADR-034 implemented by the adapters (CMP-010, CMP-011), the catalog, inventory and lifecycle
 * store already decorated by the observability of CMP-018.
 */
public record TicketingPorts(
        EventCatalog eventCatalog,
        TicketInventory ticketInventory,
        OrderLifecycleStore lifecycleStore,
        OrderReader orderReader,
        IdempotencyStore idempotencyStore,
        OrderQueuePublisher orderPublisher,
        ProvisioningQueuePublisher provisioningPublisher) {
}
