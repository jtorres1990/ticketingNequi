package com.nequi.ticketing.application.port.out;

import java.time.Instant;
import java.util.Objects;

/**
 * Lease of the active PaymentAttempt ({@code paymentLeaseOwner}, {@code paymentLeaseUntilMs};
 * BR-020, ADR-027, ADR-029). The owner identifies one processing of a message by one worker instance.
 */
public record PaymentLease(String owner, Instant until) {

    public PaymentLease {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(until, "until");
    }

    /** A lease is in force while {@code until} has not passed (AP-013 claims only when {@code until < now}). */
    public boolean inForceAt(Instant now) {
        return !until.isBefore(now);
    }
}
