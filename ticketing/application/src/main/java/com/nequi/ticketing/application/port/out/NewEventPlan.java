package com.nequi.ticketing.application.port.out;

import com.nequi.ticketing.domain.audit.AuditRecord;
import com.nequi.ticketing.domain.event.Event;
import java.time.Instant;
import java.util.Objects;

/**
 * AP-001: Event in {@code PROVISIONING}, its creation idempotency record and its audit, written
 * together with "does not exist" conditions on the three items (ADR-024, ADR-027, ADR-031).
 */
public record NewEventPlan(
        Event event,
        IdempotencyRecord idempotency,
        AuditRecord audit,
        Instant createdAt,
        String createdBy,
        int availableAtCreation,
        int complimentaryCount,
        int totalBatches) {

    public NewEventPlan {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(idempotency, "idempotency");
        Objects.requireNonNull(audit, "audit");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(createdBy, "createdBy");
        if (event.provisioningStatus() != Event.ProvisioningStatus.PROVISIONING) {
            throw new IllegalArgumentException("a new Event starts in PROVISIONING");
        }
        if (availableAtCreation + complimentaryCount != event.capacity() || totalBatches < 1) {
            throw new IllegalArgumentException("inventory counts must match the Event capacity");
        }
    }
}
