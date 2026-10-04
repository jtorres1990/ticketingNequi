package com.nequi.ticketing.application.port.in;

import com.nequi.ticketing.domain.event.Event.ProvisioningStatus;
import java.time.Instant;

/**
 * Provisioning status of an Event with its progress (FR-020, OpenAPI v2 {@code EventProvisioningStatus}).
 * {@code provisionedTickets} is derived from the confirmed provisioning batches and equals
 * {@code capacity} once {@code ENABLED}.
 */
public record ProvisioningStatusView(
        String eventId,
        ProvisioningStatus provisioningStatus,
        int capacity,
        int complimentaryTickets,
        int provisionedTickets,
        Instant createdAt,
        Instant enabledAt,
        Instant failedAt,
        FailureCause failureCause) {

    /** Functional cause of an Event in {@code FAILED}, without technical detail. */
    public enum FailureCause {
        PROVISIONING_FAILED
    }
}
