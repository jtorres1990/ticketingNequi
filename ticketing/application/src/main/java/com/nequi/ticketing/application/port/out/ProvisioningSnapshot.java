package com.nequi.ticketing.application.port.out;

import com.nequi.ticketing.domain.event.Event;
import java.time.Instant;
import java.util.Objects;

/**
 * AP-023: Event with its technical provisioning attributes, read with strong consistency: lease
 * ({@code provisioningLeaseOwner}, {@code provisioningLeaseUntilMs}), progress ({@code provisionedBatches},
 * {@code lastProgressAtMs}), stalled republications and purge marker (ADR-024).
 */
public record ProvisioningSnapshot(
        Event event,
        Instant createdAt,
        int provisionedBatches,
        Instant enabledAt,
        Instant failedAt,
        String leaseOwner,
        Instant leaseUntil,
        Instant lastProgressAt,
        int republishCount,
        Instant ticketsPurgedAt) {

    public ProvisioningSnapshot {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(createdAt, "createdAt");
        if (provisionedBatches < 0) {
            throw new IllegalArgumentException("provisionedBatches cannot be negative");
        }
        if (republishCount < 0) {
            throw new IllegalArgumentException("republishCount cannot be negative");
        }
        if ((leaseOwner == null) != (leaseUntil == null)) {
            throw new IllegalArgumentException("lease owner and expiry are set together");
        }
    }

    public ProvisioningSnapshot(Event event, Instant createdAt, int provisionedBatches, Instant enabledAt,
            Instant failedAt) {
        this(event, createdAt, provisionedBatches, enabledAt, failedAt, null, null, null, 0, null);
    }

    /** Last provisioning progress; the creation instant until the first lease or batch (ADR-024). */
    public Instant progressReference() {
        return lastProgressAt == null ? createdAt : lastProgressAt;
    }

    /** True when another owner holds a lease that has not expired at {@code now}. */
    public boolean leaseHeldByOtherAt(String owner, Instant now) {
        return leaseOwner != null && !leaseOwner.equals(owner) && !leaseUntil.isBefore(now);
    }
}
