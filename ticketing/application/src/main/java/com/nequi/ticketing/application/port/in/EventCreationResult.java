package com.nequi.ticketing.application.port.in;

import java.util.Objects;

/**
 * API-001 result: an accepted Event in {@code PROVISIONING} ({@code replayed = false}) or an idempotent
 * replay with the current provisioning status ({@code replayed = true}) (ADR-024, ADR-027, ADR-035).
 */
public record EventCreationResult(ProvisioningStatusView status, boolean replayed) {

    public EventCreationResult {
        Objects.requireNonNull(status, "status");
    }
}
