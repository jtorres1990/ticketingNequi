package com.nequi.ticketing.domain.event;

import static com.nequi.ticketing.domain.shared.DomainChecks.required;

import com.nequi.ticketing.domain.error.InvalidStateTransitionException;
import com.nequi.ticketing.domain.error.ValidationException;
import java.time.Instant;

public record Event(
        String eventId,
        String name,
        String venue,
        Instant startsAt,
        int capacity,
        InventoryDefinition inventoryDefinition,
        int availabilityShards,
        ProvisioningStatus provisioningStatus) {

    public static Event create(
            String eventId,
            String name,
            String venue,
            Instant startsAt,
            int capacity,
            InventoryDefinition definition,
            Instant now,
            InventoryLimits limits) {
        required(eventId, "eventId");
        required(name, "name");
        required(venue, "venue");
        required(startsAt, "startsAt");
        required(now, "now");
        required(definition, "inventoryDefinition").validate(capacity, limits);
        if (!startsAt.isAfter(now)) {
            throw new ValidationException("startsAt must be in the future");
        }
        return new Event(eventId, name, venue, startsAt, capacity, definition,
                ShardingPolicy.availabilityShards(capacity), ProvisioningStatus.PROVISIONING);
    }

    public Event enable(int verifiedTicketCount) {
        requireProvisioning();
        if (verifiedTicketCount != capacity) {
            throw new ValidationException("verified ticket count must equal capacity");
        }
        return withStatus(ProvisioningStatus.ENABLED);
    }

    public Event fail() {
        requireProvisioning();
        return withStatus(ProvisioningStatus.FAILED);
    }

    public boolean visibleAt(Instant now) {
        return provisioningStatus == ProvisioningStatus.ENABLED && startsAt.isAfter(required(now, "now"));
    }

    public boolean isPastAt(Instant now) {
        return !startsAt.isAfter(required(now, "now"));
    }

    public boolean availabilityIsVisible() {
        return provisioningStatus == ProvisioningStatus.ENABLED;
    }

    private void requireProvisioning() {
        if (provisioningStatus != ProvisioningStatus.PROVISIONING) {
            throw new InvalidStateTransitionException("an Event can transition only from PROVISIONING");
        }
    }

    private Event withStatus(ProvisioningStatus status) {
        return new Event(eventId, name, venue, startsAt, capacity, inventoryDefinition, availabilityShards, status);
    }

    public enum ProvisioningStatus {
        PROVISIONING,
        ENABLED,
        FAILED;

        public boolean terminal() {
            return this != PROVISIONING;
        }
    }
}
