package com.nequi.ticketing.application.port.out;

import java.util.List;
import java.util.Objects;

/**
 * AP-025 result: number of generated keys found with their initial state, and the identifiers of the
 * Tickets that are missing or not in the initial state assigned by the definition (ADR-024, VAL-009).
 */
public record InventoryVerification(int verifiedCount, List<String> invalidTicketIds) {

    public InventoryVerification {
        invalidTicketIds = List.copyOf(Objects.requireNonNull(invalidTicketIds, "invalidTicketIds"));
        if (verifiedCount < 0) {
            throw new IllegalArgumentException("verifiedCount cannot be negative");
        }
    }

    public boolean complete() {
        return invalidTicketIds.isEmpty();
    }
}
