package com.nequi.ticketing.domain.shared;

import static com.nequi.ticketing.domain.shared.DomainChecks.required;

import com.nequi.ticketing.domain.event.InventoryDefinition;
import com.nequi.ticketing.domain.order.PurchaseRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

public final class ContentHash {

    private ContentHash() {
    }

    public static String purchase(PurchaseRequest request) {
        required(request, "purchaseRequest");
        StringBuilder canonical = new StringBuilder();
        append(canonical, request.eventId());
        request.ticketIds().stream().sorted().forEach(value -> append(canonical, value));
        return sha256(canonical.toString());
    }

    public static String event(
            String name,
            String venue,
            Instant startsAt,
            int capacity,
            InventoryDefinition definition) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, required(name, "name"));
        append(canonical, required(venue, "venue"));
        append(canonical, required(startsAt, "startsAt").toString());
        append(canonical, Integer.toString(capacity));
        required(definition, "inventoryDefinition").sections().stream()
                .sorted(java.util.Comparator.comparing(InventoryDefinition.Section::code))
                .forEach(section -> {
                    append(canonical, section.code());
                    section.rows().stream().sorted(java.util.Comparator.comparing(InventoryDefinition.Row::label))
                            .forEach(row -> {
                                append(canonical, row.label());
                                append(canonical, Integer.toString(row.seats()));
                            });
                });
        definition.complimentaryRanges().stream()
                .sorted(java.util.Comparator.comparing(InventoryDefinition.ComplimentaryRange::section)
                        .thenComparing(InventoryDefinition.ComplimentaryRange::row)
                        .thenComparingInt(InventoryDefinition.ComplimentaryRange::fromSeat)
                        .thenComparingInt(InventoryDefinition.ComplimentaryRange::toSeat))
                .forEach(range -> {
                    append(canonical, range.section());
                    append(canonical, range.row());
                    append(canonical, Integer.toString(range.fromSeat()));
                    append(canonical, Integer.toString(range.toSeat()));
                });
        return sha256(canonical.toString());
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value);
    }

    private static String sha256(String canonical) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", impossible);
        }
    }
}
