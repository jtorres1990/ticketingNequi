package com.nequi.ticketing.application.port.in;

import com.nequi.ticketing.domain.event.InventoryDefinition;
import java.time.Instant;

/**
 * API-001 command. {@code adminSubject} is the JWT subject of the authenticated ADMIN, never a value
 * sent by the client (ADR-032); {@code correlationId} is the request trace identifier.
 */
public record CreateEventCommand(
        String adminSubject,
        String idempotencyKey,
        String name,
        String venue,
        Instant startsAt,
        int capacity,
        InventoryDefinition inventory,
        String correlationId) {
}
