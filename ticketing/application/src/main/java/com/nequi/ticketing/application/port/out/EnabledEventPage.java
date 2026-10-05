package com.nequi.ticketing.application.port.out;

import com.nequi.ticketing.domain.event.Event;
import java.util.List;
import java.util.Objects;

/**
 * AP-004 page of Events in {@code ENABLED} ordered by start instant; the cursor is opaque and present only
 * when more Events exist. The Events carry the listing projection ({@code GSI1}, ADR-022): metadata,
 * capacity, availability shards and status, but not necessarily the inventory definition.
 */
public record EnabledEventPage(List<Event> events, String nextCursor) {

    public EnabledEventPage {
        events = List.copyOf(Objects.requireNonNull(events, "events"));
    }
}
