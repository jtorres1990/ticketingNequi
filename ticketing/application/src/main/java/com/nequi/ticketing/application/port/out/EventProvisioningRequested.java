package com.nequi.ticketing.application.port.out;

import java.util.Objects;

/** MSG-002 {@code EventProvisioningRequested}, schema version 1: only the Event identifier (ADR-024). */
public record EventProvisioningRequested(String eventId, String correlationId) {

    public EventProvisioningRequested {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(correlationId, "correlationId");
    }
}
