package com.nequi.ticketing.application.port.out;

import com.nequi.ticketing.domain.audit.AuditRecord;
import com.nequi.ticketing.domain.event.Event;
import com.nequi.ticketing.domain.event.Event.ProvisioningStatus;
import java.time.Instant;
import java.util.Objects;

/**
 * AP-003 "Enable" (ST-012, 2 items): Event {@code ENABLED} with {@code enabledAt}, reindexed under
 * {@code EVENTS#ENABLED} and without lease, plus audit {@code EVENT_ENABLED}. Guards: Event
 * {@code PROVISIONING} with the lease owned by {@code leaseOwner}; audit does not exist (ADR-024).
 */
public record EnablementPlan(Event current, Event enabled, String leaseOwner, AuditRecord audit, Instant enabledAt) {

    public EnablementPlan {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(enabled, "enabled");
        Objects.requireNonNull(leaseOwner, "leaseOwner");
        Objects.requireNonNull(audit, "audit");
        Objects.requireNonNull(enabledAt, "enabledAt");
        if (!current.eventId().equals(enabled.eventId()) || enabled.provisioningStatus() != ProvisioningStatus.ENABLED) {
            throw new IllegalArgumentException("the enabled Event must be the enablement of the current Event");
        }
    }
}
