package com.nequi.ticketing.application.port.in;

import java.util.List;

/**
 * API-004 command. {@code customerId} is the JWT subject of the authenticated CUSTOMER (VAL-011,
 * ADR-032); {@code correlationId} is the request trace identifier.
 */
public record StartPurchaseCommand(
        String customerId,
        String eventId,
        List<String> ticketIds,
        String idempotencyKey,
        String correlationId) {
}
