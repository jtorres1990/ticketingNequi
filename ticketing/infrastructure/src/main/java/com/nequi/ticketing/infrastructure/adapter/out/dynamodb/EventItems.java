package com.nequi.ticketing.infrastructure.adapter.out.dynamodb;

import static com.nequi.ticketing.infrastructure.adapter.out.dynamodb.Attributes.instant;
import static com.nequi.ticketing.infrastructure.adapter.out.dynamodb.Attributes.millis;
import static com.nequi.ticketing.infrastructure.adapter.out.dynamodb.Attributes.n;
import static com.nequi.ticketing.infrastructure.adapter.out.dynamodb.Attributes.s;

import com.nequi.ticketing.application.port.out.NewEventPlan;
import com.nequi.ticketing.application.port.out.ProvisioningSnapshot;
import com.nequi.ticketing.application.port.out.StalledProvisioning;
import com.nequi.ticketing.domain.event.Event;
import com.nequi.ticketing.domain.event.Event.ProvisioningStatus;
import com.nequi.ticketing.domain.event.InventoryDefinition;
import com.nequi.ticketing.domain.event.InventoryDefinition.ComplimentaryRange;
import com.nequi.ticketing.domain.event.InventoryDefinition.Row;
import com.nequi.ticketing.domain.event.InventoryDefinition.Section;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Event item of {@code ticketing.data-model.v2.md} §3 ({@code EVENT#<eventId>} / {@code #META}): metadata,
 * compact inventory definition, provisioning lifecycle, lease, progress and {@code GSI1} keys (ADR-022,
 * ADR-024). The {@code GSI1} projection carries no definition: Events read from the index have an empty one.
 */
final class EventItems {

    static final String ENTITY = "EVENT";
    static final String ENTITY_TYPE = "entityType";
    static final String EVENT_ID = "eventId";
    static final String NAME = "name";
    static final String VENUE = "venue";
    static final String STARTS_AT = "startsAt";
    static final String STARTS_AT_MS = "startsAtMs";
    static final String CAPACITY = "capacity";
    static final String AVAILABLE_AT_CREATION = "availableAtCreation";
    static final String COMPLIMENTARY_COUNT = "complimentaryCount";
    static final String INVENTORY_DEFINITION = "inventoryDefinition";
    static final String AVAILABILITY_SHARDS = "availabilityShards";
    static final String TOTAL_BATCHES = "totalBatches";
    static final String STATUS = "provisioningStatus";
    static final String LEASE_OWNER = "provisioningLeaseOwner";
    static final String LEASE_UNTIL_MS = "provisioningLeaseUntilMs";
    static final String PROVISIONED_BATCHES = "provisionedBatches";
    static final String LAST_PROGRESS_AT_MS = "lastProgressAtMs";
    static final String REPUBLISH_COUNT = "provisioningRepublishCount";
    static final String FAILURE_REASON = "provisioningFailureReason";
    static final String CREATED_AT = "createdAt";
    static final String CREATED_BY = "createdBy";
    static final String ENABLED_AT = "enabledAt";
    static final String FAILED_AT = "failedAt";
    static final String TICKETS_PURGED_AT = "ticketsPurgedAt";
    static final String GSI1PK = "GSI1PK";
    static final String GSI1SK = "GSI1SK";

    /** Functional cause shown by API-006 for a failed provisioning (ADR-024). */
    static final String PROVISIONING_FAILED = "PROVISIONING_FAILED";

    private static final InventoryDefinition NOT_PROJECTED = new InventoryDefinition(List.of(), List.of());

    private EventItems() {
    }

    /** AP-001: Event in {@code PROVISIONING}, indexed under {@code EVENTS#PROVISIONING}; progress starts at creation. */
    static Map<String, AttributeValue> newEvent(NewEventPlan plan) {
        Event event = plan.event();
        Map<String, AttributeValue> item = new HashMap<>(Keys.meta(Keys.event(event.eventId())));
        item.put(ENTITY_TYPE, s(ENTITY));
        item.put(EVENT_ID, s(event.eventId()));
        item.put(NAME, s(event.name()));
        item.put(VENUE, s(event.venue()));
        item.put(STARTS_AT, instant(event.startsAt()));
        item.put(STARTS_AT_MS, millis(event.startsAt()));
        item.put(CAPACITY, n(event.capacity()));
        item.put(AVAILABLE_AT_CREATION, n(plan.availableAtCreation()));
        item.put(COMPLIMENTARY_COUNT, n(plan.complimentaryCount()));
        item.put(INVENTORY_DEFINITION, definition(event.inventoryDefinition()));
        item.put(AVAILABILITY_SHARDS, n(event.availabilityShards()));
        item.put(TOTAL_BATCHES, n(plan.totalBatches()));
        item.put(STATUS, s(ProvisioningStatus.PROVISIONING.name()));
        item.put(PROVISIONED_BATCHES, n(0));
        item.put(LAST_PROGRESS_AT_MS, millis(plan.createdAt()));
        item.put(REPUBLISH_COUNT, n(0));
        item.put(CREATED_AT, instant(plan.createdAt()));
        item.put(CREATED_BY, s(plan.createdBy()));
        item.put(GSI1PK, s(Keys.EVENTS_PROVISIONING));
        item.put(GSI1SK, s(Keys.eventLifecycleSort(plan.createdAt(), event.eventId())));
        return item;
    }

    /** Event from the table item (full definition) or from the {@code GSI1} projection (no definition). */
    static Event event(Map<String, AttributeValue> item) {
        AttributeValue definition = item.get(INVENTORY_DEFINITION);
        return new Event(
                Attributes.string(item, EVENT_ID),
                Attributes.string(item, NAME),
                Attributes.string(item, VENUE),
                Attributes.instant(item, STARTS_AT),
                Attributes.integer(item, CAPACITY),
                definition == null ? NOT_PROJECTED : definition(definition),
                Attributes.integer(item, AVAILABILITY_SHARDS),
                ProvisioningStatus.valueOf(Attributes.string(item, STATUS)));
    }

    static ProvisioningSnapshot snapshot(Map<String, AttributeValue> item) {
        return new ProvisioningSnapshot(
                event(item),
                Attributes.instant(item, CREATED_AT),
                Attributes.integer(item, PROVISIONED_BATCHES),
                Attributes.optionalInstant(item, ENABLED_AT),
                Attributes.optionalInstant(item, FAILED_AT),
                Attributes.optionalString(item, LEASE_OWNER),
                Attributes.optionalMillis(item, LEASE_UNTIL_MS),
                Attributes.optionalMillis(item, LAST_PROGRESS_AT_MS),
                Attributes.integer(item, REPUBLISH_COUNT),
                Attributes.optionalInstant(item, TICKETS_PURGED_AT));
    }

    /** AP-018 candidate from the {@code GSI1} projection. */
    static StalledProvisioning stalled(Map<String, AttributeValue> item) {
        return new StalledProvisioning(
                Attributes.string(item, EVENT_ID),
                Attributes.optionalMillis(item, LAST_PROGRESS_AT_MS),
                Attributes.integer(item, REPUBLISH_COUNT));
    }

    static AttributeValue definition(InventoryDefinition definition) {
        List<AttributeValue> sections = definition.sections().stream()
                .map(section -> AttributeValue.fromM(Map.of(
                        "code", s(section.code()),
                        "rows", AttributeValue.fromL(section.rows().stream()
                                .map(row -> AttributeValue.fromM(Map.of("label", s(row.label()), "seats", n(row.seats()))))
                                .toList()))))
                .toList();
        List<AttributeValue> ranges = definition.complimentaryRanges().stream()
                .map(range -> AttributeValue.fromM(Map.of(
                        "section", s(range.section()),
                        "row", s(range.row()),
                        "fromSeat", n(range.fromSeat()),
                        "toSeat", n(range.toSeat()))))
                .toList();
        return AttributeValue.fromM(Map.of(
                "sections", AttributeValue.fromL(sections),
                "complimentaryRanges", AttributeValue.fromL(ranges)));
    }

    static InventoryDefinition definition(AttributeValue value) {
        Map<String, AttributeValue> definition = value.m();
        List<Section> sections = definition.get("sections").l().stream()
                .map(AttributeValue::m)
                .map(section -> new Section(
                        section.get("code").s(),
                        section.get("rows").l().stream()
                                .map(AttributeValue::m)
                                .map(row -> new Row(row.get("label").s(), Integer.parseInt(row.get("seats").n())))
                                .toList()))
                .toList();
        List<ComplimentaryRange> ranges = definition.get("complimentaryRanges").l().stream()
                .map(AttributeValue::m)
                .map(range -> new ComplimentaryRange(
                        range.get("section").s(),
                        range.get("row").s(),
                        Integer.parseInt(range.get("fromSeat").n()),
                        Integer.parseInt(range.get("toSeat").n())))
                .toList();
        return new InventoryDefinition(sections, ranges);
    }
}
