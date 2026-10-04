package com.nequi.ticketing.application.port.out;

import java.time.Instant;
import java.util.Objects;

/**
 * MSG-001 {@code OrderProcessingRequested}, schema version 1: pure reference to the Order. Transport
 * fields ({@code schemaVersion}, {@code messageType}, trace context) are added by the adapter.
 */
public record OrderProcessingRequested(
        String orderId,
        String eventId,
        Instant occurredAt,
        String correlationId,
        Publisher publisher) {

    public OrderProcessingRequested {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(publisher, "publisher");
    }

    /** Diagnostic {@code publisher} message attribute. */
    public enum Publisher {
        API,
        SWEEP
    }
}
