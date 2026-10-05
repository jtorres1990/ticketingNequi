package com.nequi.ticketing.infrastructure.adapter.out.dynamodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nequi.ticketing.application.port.out.IdempotencyRecord;
import com.nequi.ticketing.application.port.out.NewEventPlan;
import com.nequi.ticketing.application.port.out.TransactionOutcome;
import com.nequi.ticketing.application.testdouble.MutableClock;
import com.nequi.ticketing.domain.audit.AuditRecord.Actor;
import com.nequi.ticketing.domain.audit.AuditRecord.ActorType;
import com.nequi.ticketing.domain.audit.AuditRecord.AuditCode;
import com.nequi.ticketing.domain.audit.AuditRecordBuilder;
import com.nequi.ticketing.domain.event.Event;
import com.nequi.ticketing.domain.event.InventoryDefinition;
import com.nequi.ticketing.domain.event.InventoryDefinition.ComplimentaryRange;
import com.nequi.ticketing.domain.event.InventoryDefinition.Row;
import com.nequi.ticketing.domain.event.InventoryDefinition.Section;
import com.nequi.ticketing.domain.event.InventoryLimits;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;

/**
 * SPK-015 (ticketing.architecture.v2.md §13 item 8, item size): the Event item with the largest compact
 * definition allowed by VAL-013 / ADR-024 (100 sections, 2,000 rows, 50,000 seats, 500 complimentary
 * ranges, maximum code lengths of OpenAPI v2) is written by AP-001 and read back, with its size well below
 * the 400 KB item limit, which DynamoDB Local enforces (control write of a larger item).
 */
class EventItemSizeSpikeIT {

    private static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");

    @Test
    @DisplayName("SPK-015 VAL-013 ADR-024 the maximum Event definition fits in one item with ample margin below 400 KB")
    void maximumEventFitsInOneItem() {
        DynamoDbAsyncClient client = DynamoDbLocalSupport.client();
        String table = DynamoDbLocalSupport.createTable();
        DynamoDbPersistence persistence = DynamoDbPersistence.create(client, DynamoDbAdapterSettings.deployed(table),
                new MutableClock(NOW));

        List<Section> sections = new ArrayList<>();
        List<ComplimentaryRange> ranges = new ArrayList<>();
        for (int section = 0; section < 100; section++) {
            String code = "S%07d".formatted(section);
            List<Row> rows = new ArrayList<>();
            for (int row = 0; row < 20; row++) {
                String label = "R%05d".formatted(row);
                rows.add(new Row(label, 25));
                if (ranges.size() < 500 && row < 5) {
                    ranges.add(new ComplimentaryRange(code, label, 1, 2));
                }
            }
            sections.add(new Section(code, rows));
        }
        InventoryDefinition definition = new InventoryDefinition(sections, ranges);
        Event event = Event.create(UUID.randomUUID().toString(), "N".repeat(200), "V".repeat(200),
                NOW.plus(Duration.ofDays(30)), 50_000, definition, NOW, InventoryLimits.DEPLOYED);
        int complimentary = definition.complimentarySeatCount();
        NewEventPlan plan = new NewEventPlan(event,
                new IdempotencyRecord("admin", "spike-key-0000001", event.eventId(), "h", NOW, NOW.plus(Duration.ofHours(24))),
                new AuditRecordBuilder().code(AuditCode.EVENT_PROVISIONING_REQUESTED).event(event.eventId())
                        .actor(new Actor(ActorType.ADMIN, "admin")).correlation("c").occurredAt(NOW).build(),
                NOW, "admin", 50_000 - complimentary, complimentary, 500);

        assertThat(persistence.eventCatalog().create(plan).block()).isEqualTo(TransactionOutcome.applied());
        assertThat(persistence.eventCatalog().findEvent(event.eventId()).block()).isEqualTo(event);

        long size = itemSize(EventItems.newEvent(plan));
        System.out.println("SPK-015 maximum Event item size (estimate by the documented sizing rules): " + size
                + " bytes = " + (size * 100 / (400 * 1024)) + "% of 400 KB; rows=2000, ranges=" + ranges.size());
        assertThat(size).isLessThan(200 * 1024);

        Map<String, AttributeValue> oversized = new HashMap<>(Keys.meta("SPK015#" + UUID.randomUUID()));
        oversized.put("payload", AttributeValue.fromS("x".repeat(401 * 1024)));
        assertThatThrownBy(() -> client.putItem(builder -> builder.tableName(table).item(oversized)).join())
                .cause().isInstanceOf(DynamoDbException.class);
    }

    /** DynamoDB item sizing rules: names and string bytes; numbers about half their digits plus one; 3 bytes per list or map plus 1 per element. */
    static long itemSize(Map<String, AttributeValue> item) {
        return item.entrySet().stream().mapToLong(entry -> utf8(entry.getKey()) + size(entry.getValue())).sum();
    }

    private static long size(AttributeValue value) {
        if (value.s() != null) {
            return utf8(value.s());
        }
        if (value.n() != null) {
            return (value.n().replace("-", "").replace(".", "").length() + 1) / 2 + 1;
        }
        if (value.bool() != null) {
            return 1;
        }
        if (value.hasL()) {
            return 3 + value.l().stream().mapToLong(element -> 1 + size(element)).sum();
        }
        if (value.hasM()) {
            return 3 + value.m().entrySet().stream().mapToLong(entry -> 1 + utf8(entry.getKey()) + size(entry.getValue())).sum();
        }
        throw new IllegalArgumentException("unsupported attribute type " + value);
    }

    private static long utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }
}
