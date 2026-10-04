package com.nequi.ticketing.application.port.out;

import com.nequi.ticketing.domain.event.Event;
import java.time.Instant;
import java.util.Objects;

/** AP-023: Event with its technical provisioning attributes, read with strong consistency. */
public record ProvisioningSnapshot(
        Event event,
        Instant createdAt,
        int provisionedBatches,
        Instant enabledAt,
        Instant failedAt) {

    public ProvisioningSnapshot {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(createdAt, "createdAt");
        if (provisionedBatches < 0) {
            throw new IllegalArgumentException("provisionedBatches cannot be negative");
        }
    }
}
