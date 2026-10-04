package com.nequi.ticketing.application.port.in;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Informative availability snapshot (FR-012, BR-018, ADR-040): count of Tickets in {@code AVAILABLE}
 * at {@code generatedAt} (cached up to the configured age) and one page of Tickets in
 * {@code AVAILABLE} only.
 */
public record AvailabilityView(
        String eventId,
        String name,
        String venue,
        Instant startsAt,
        int capacity,
        List<String> sections,
        long availableCount,
        Instant generatedAt,
        boolean informative,
        String sectionFilter,
        List<AvailableTicketView> items,
        String nextCursor) {

    public AvailabilityView {
        sections = List.copyOf(Objects.requireNonNull(sections, "sections"));
        items = List.copyOf(Objects.requireNonNull(items, "items"));
    }
}
