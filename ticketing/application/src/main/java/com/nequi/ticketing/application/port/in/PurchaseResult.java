package com.nequi.ticketing.application.port.in;

import java.util.Objects;

/**
 * API-004 result: an Order created by this request ({@code replayed = false}; {@code CREATED}, or
 * {@code FAILED} after a definitive enqueue failure) or an idempotent replay of an accepted request
 * with the Order in its current state ({@code replayed = true}) (ADR-026, ADR-027, ADR-035).
 */
public record PurchaseResult(OrderView order, boolean replayed) {

    public PurchaseResult {
        Objects.requireNonNull(order, "order");
    }
}
