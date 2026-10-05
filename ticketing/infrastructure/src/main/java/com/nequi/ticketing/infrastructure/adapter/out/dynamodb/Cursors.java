package com.nequi.ticketing.infrastructure.adapter.out.dynamodb;

import com.nequi.ticketing.domain.error.ValidationException;
import com.nequi.ticketing.domain.event.Event;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Opaque, strictly validated cursors (ADR-040): a version, the kind of listing, the Event and filter they
 * were issued for, the shard and the last key. A cursor that does not decode, belongs to another Event or
 * filter, or points outside the Event shards is a {@link ValidationException} ({@code VALIDATION_ERROR}).
 */
final class Cursors {

    private static final String VERSION = "1";
    private static final String EVENTS = "E";
    private static final String AVAILABILITY = "A";
    private static final int MAXIMUM_LENGTH = 1024;
    private static final Pattern TICKET_ID = Pattern.compile("^[A-Za-z0-9]{1,8}-[A-Za-z0-9]{1,6}-[0-9]{1,4}$");
    private static final Pattern EVENT_ID = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

    private Cursors() {
    }

    // ------------------------------------------------------------------ AP-004

    static String events(String lifecycleSortKey) {
        return encode(VERSION + "|" + EVENTS + "|" + lifecycleSortKey);
    }

    /** {@code GSI1SK} of the last Event returned by the previous page. */
    static String eventsPosition(String cursor) {
        String[] parts = decode(cursor, 3);
        if (!EVENTS.equals(parts[1])) {
            throw invalid();
        }
        String sortKey = parts[2];
        int separator = sortKey.indexOf('#');
        if (separator < 0 || !EVENT_ID.matcher(sortKey.substring(separator + 1)).matches()) {
            throw invalid();
        }
        try {
            Keys.parseSortable(sortKey.substring(0, separator));
        } catch (DateTimeParseException malformed) {
            throw invalid();
        }
        return sortKey;
    }

    static Map<String, AttributeValue> enabledEventsStartKey(String lifecycleSortKey) {
        String eventId = lifecycleSortKey.substring(lifecycleSortKey.indexOf('#') + 1);
        Map<String, AttributeValue> key = new HashMap<>(Keys.meta(Keys.event(eventId)));
        key.put(EventItems.GSI1PK, AttributeValue.fromS(Keys.EVENTS_ENABLED));
        key.put(EventItems.GSI1SK, AttributeValue.fromS(lifecycleSortKey));
        return key;
    }

    // ------------------------------------------------------------------ AP-020

    /** Position after which the next availability page starts. */
    record AvailabilityPosition(int shard, String ticketId) {
    }

    static String availability(Event event, String section, int shard, String ticketId) {
        return encode(String.join("|", VERSION, AVAILABILITY, event.eventId(), section == null ? "" : section,
                Integer.toString(shard), ticketId));
    }

    static AvailabilityPosition availabilityPosition(String cursor, Event event, String section) {
        String[] parts = decode(cursor, 6);
        String expectedSection = section == null ? "" : section;
        if (!AVAILABILITY.equals(parts[1]) || !event.eventId().equals(parts[2]) || !expectedSection.equals(parts[3])) {
            throw invalid();
        }
        int shard;
        try {
            shard = Integer.parseInt(parts[4]);
        } catch (NumberFormatException malformed) {
            throw invalid();
        }
        String ticketId = parts[5];
        if (shard < 0 || shard >= event.availabilityShards() || !TICKET_ID.matcher(ticketId).matches()
                || (section != null && !ticketId.startsWith(section + "-"))
                || Keys.availabilityShard(ticketId, event.availabilityShards()) != shard) {
            throw invalid();
        }
        return new AvailabilityPosition(shard, ticketId);
    }

    static Map<String, AttributeValue> availabilityStartKey(Event event, AvailabilityPosition position) {
        Map<String, AttributeValue> key = new HashMap<>(TicketItems.key(event.eventId(), position.ticketId()));
        key.put(TicketItems.GSI2PK, AttributeValue.fromS(Keys.availability(event.eventId(), position.shard())));
        key.put(TicketItems.GSI2SK, AttributeValue.fromS(TicketItems.availabilitySort(position.ticketId())));
        return key;
    }

    // ------------------------------------------------------------------ encoding

    private static String encode(String payload) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    private static String[] decode(String cursor, int expectedParts) {
        if (cursor.isEmpty() || cursor.length() > MAXIMUM_LENGTH) {
            throw invalid();
        }
        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            throw invalid();
        }
        String[] parts = payload.split("\\|", -1);
        if (parts.length != expectedParts || !VERSION.equals(parts[0])) {
            throw invalid();
        }
        return parts;
    }

    private static ValidationException invalid() {
        return new ValidationException("cursor is invalid");
    }
}
