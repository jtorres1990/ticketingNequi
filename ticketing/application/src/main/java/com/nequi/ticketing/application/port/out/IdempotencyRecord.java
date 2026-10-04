package com.nequi.ticketing.application.port.out;

import java.time.Instant;
import java.util.Objects;

/**
 * Idempotency record of a purchase ({@code IDEM#}) or of an Event creation ({@code IDEMEVT#}) (ADR-027).
 * {@code ownerId} is the JWT subject; {@code resourceId} is the Order or Event identifier.
 */
public record IdempotencyRecord(
        String ownerId,
        String idempotencyKey,
        String resourceId,
        String requestHash,
        Instant createdAt,
        Instant expiresAt) {

    public IdempotencyRecord {
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(requestHash, "requestHash");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    public boolean sameContent(String candidateHash) {
        return requestHash.equals(candidateHash);
    }
}
