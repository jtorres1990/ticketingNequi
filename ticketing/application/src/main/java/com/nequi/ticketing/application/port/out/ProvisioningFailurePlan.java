package com.nequi.ticketing.application.port.out;

import com.nequi.ticketing.domain.audit.AuditRecord;
import com.nequi.ticketing.domain.event.Event;
import com.nequi.ticketing.domain.event.Event.ProvisioningStatus;
import java.time.Instant;
import java.util.Objects;

/**
 * AP-026 "Mark FAILED" (ST-013, 2 items): Event {@code FAILED} with cause {@code PROVISIONING_FAILED} and
 * {@code failedAt}, reindexed under {@code EVENTS#FAILED} and without lease, plus audit
 * {@code EVENT_PROVISIONING_FAILED}. Guard: Event {@code PROVISIONING} (ADR-024).
 */
public record ProvisioningFailurePlan(Event current, Event failed, AuditRecord audit, Instant failedAt) {

    public ProvisioningFailurePlan {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(failed, "failed");
        Objects.requireNonNull(audit, "audit");
        Objects.requireNonNull(failedAt, "failedAt");
        if (!current.eventId().equals(failed.eventId()) || failed.provisioningStatus() != ProvisioningStatus.FAILED) {
            throw new IllegalArgumentException("the failed Event must be the failure of the current Event");
        }
    }
}
