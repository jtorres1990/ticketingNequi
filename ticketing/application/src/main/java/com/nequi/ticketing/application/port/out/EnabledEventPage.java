package com.nequi.ticketing.application.port.out;

import com.nequi.ticketing.domain.event.Event;
import java.util.List;
import java.util.Objects;

/** AP-004 page of Events in {@code ENABLED} ordered by start instant; the cursor is opaque. */
public record EnabledEventPage(List<Event> events, String nextCursor) {

    public EnabledEventPage {
        events = List.copyOf(Objects.requireNonNull(events, "events"));
    }
}
