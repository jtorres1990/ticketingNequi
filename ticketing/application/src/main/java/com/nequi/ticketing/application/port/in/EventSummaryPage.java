package com.nequi.ticketing.application.port.in;

import java.util.List;
import java.util.Objects;

/** API-002 page of future Events in {@code ENABLED}. */
public record EventSummaryPage(List<EventSummaryView> items, String nextCursor) {

    public EventSummaryPage {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
    }
}
