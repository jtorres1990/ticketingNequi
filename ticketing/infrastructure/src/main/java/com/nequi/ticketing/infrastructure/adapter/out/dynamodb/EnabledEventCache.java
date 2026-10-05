package com.nequi.ticketing.infrastructure.adapter.out.dynamodb;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.nequi.ticketing.domain.event.Event;
import com.nequi.ticketing.domain.event.Event.ProvisioningStatus;
import java.util.Optional;

/**
 * Bounded in-memory cache of Events in {@code ENABLED} (AP-006): an enabled Event and its definition are
 * immutable (ADR-023, ADR-024), so entries never expire; Events in any other status are never cached.
 */
final class EnabledEventCache {

    private final Cache<String, Event> events;

    EnabledEventCache(long maximumSize) {
        this.events = Caffeine.newBuilder().maximumSize(maximumSize).build();
    }

    Optional<Event> get(String eventId) {
        return Optional.ofNullable(events.getIfPresent(eventId));
    }

    /** Caches the Event only when it is {@code ENABLED} and carries its inventory definition. */
    Event remember(Event event) {
        if (event.provisioningStatus() == ProvisioningStatus.ENABLED && !event.inventoryDefinition().sections().isEmpty()) {
            events.put(event.eventId(), event);
        }
        return event;
    }
}
