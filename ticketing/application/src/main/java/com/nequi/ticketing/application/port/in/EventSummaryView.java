package com.nequi.ticketing.application.port.in;

import java.time.Instant;

/** Listed Event with the sold-out indicator instead of the available quantity (AV-001, BR-022). */
public record EventSummaryView(
        String eventId,
        String name,
        String venue,
        Instant startsAt,
        int capacity,
        boolean soldOut) {
}
