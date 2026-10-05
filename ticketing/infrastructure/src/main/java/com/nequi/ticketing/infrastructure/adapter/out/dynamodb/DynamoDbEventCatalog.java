package com.nequi.ticketing.infrastructure.adapter.out.dynamodb;

import static com.nequi.ticketing.infrastructure.adapter.out.dynamodb.Attributes.instant;
import static com.nequi.ticketing.infrastructure.adapter.out.dynamodb.Attributes.millis;
import static com.nequi.ticketing.infrastructure.adapter.out.dynamodb.Attributes.n;
import static com.nequi.ticketing.infrastructure.adapter.out.dynamodb.Attributes.s;

import com.nequi.ticketing.application.port.out.EnabledEventPage;
import com.nequi.ticketing.application.port.out.EnablementPlan;
import com.nequi.ticketing.application.port.out.EventCatalog;
import com.nequi.ticketing.application.port.out.FailedItem;
import com.nequi.ticketing.application.port.out.NewEventPlan;
import com.nequi.ticketing.application.port.out.ProvisioningFailurePlan;
import com.nequi.ticketing.application.port.out.ProvisioningSnapshot;
import com.nequi.ticketing.application.port.out.StalledProvisioning;
import com.nequi.ticketing.application.port.out.TransactionOutcome;
import com.nequi.ticketing.domain.event.Event;
import com.nequi.ticketing.domain.event.Event.ProvisioningStatus;
import com.nequi.ticketing.infrastructure.adapter.out.dynamodb.DynamoDbTable.ItemRole;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

/**
 * CMP-010 {@code EventCatalog} over DynamoDB (ADR-022, ADR-024, ADR-039, ADR-040): AP-001, AP-003, AP-004,
 * AP-006, AP-018, AP-023, AP-024, AP-026, AP-027 (query and final mark) and AP-033, with exactly the items
 * and conditions of {@code ticketing.data-model.v2.md} §5.
 */
public final class DynamoDbEventCatalog implements EventCatalog {

    private final DynamoDbTable table;
    private final EnabledEventCache cache;

    DynamoDbEventCatalog(DynamoDbTable table, EnabledEventCache cache) {
        this.table = Objects.requireNonNull(table, "table");
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    /** AP-001: Event, creation idempotency record and audit; the three must not exist (3 items). */
    @Override
    public Mono<TransactionOutcome> create(NewEventPlan plan) {
        return Mono.defer(() -> table.transact("AP-001 create Event", List.of(
                table.insert(EventItems.newEvent(plan), ItemRole.of(FailedItem.EVENT)),
                table.insert(IdempotencyItems.eventCreation(plan.idempotency()), ItemRole.of(FailedItem.IDEMPOTENCY_RECORD)),
                table.insert(AuditItems.item(plan.audit()), ItemRole.of(FailedItem.AUDIT)))));
    }

    /** AP-006: eventually consistent read, served from the cache once the Event is {@code ENABLED}. */
    @Override
    public Mono<Event> findEvent(String eventId) {
        return Mono.defer(() -> cache.get(eventId)
                .map(Mono::just)
                .orElseGet(() -> table.get("AP-006 find Event", Keys.meta(Keys.event(eventId)), false)
                        .map(EventItems::event)
                        .map(cache::remember)));
    }

    /** AP-023: strongly consistent read of the Event with its provisioning attributes. */
    @Override
    public Mono<ProvisioningSnapshot> findProvisioningSnapshot(String eventId) {
        return Mono.defer(() -> table.get("AP-023 provisioning snapshot", Keys.meta(Keys.event(eventId)), true)
                .map(EventItems::snapshot));
    }

    /**
     * AP-004: {@code GSI1PK = EVENTS#ENABLED AND GSI1SK > <now>#~}, ascending. One extra item is read to know
     * whether another page exists. Events come from the {@code GSI1} projection, without definition.
     */
    @Override
    public Mono<EnabledEventPage> listEnabledUpcoming(Instant now, int limit, String cursor) {
        return Mono.defer(() -> {
            // "<now>#~" sorts after every "<now>#<eventId>": an Event starting exactly now is not upcoming (BR-022).
            String nowKey = Keys.sortable(now) + "#~";
            String position = cursor == null ? null : Cursors.eventsPosition(cursor);
            // A position at or before "now" lies outside the key range: every item up to it is excluded anyway.
            Map<String, AttributeValue> start = position == null || position.compareTo(nowKey) <= 0
                    ? null : Cursors.enabledEventsStartKey(position);
            Expression keys = new Expression();
            QueryRequest request = QueryRequest.builder()
                    .tableName(table.name())
                    .indexName("GSI1")
                    .keyConditionExpression(keys.eq(EventItems.GSI1PK, s(Keys.EVENTS_ENABLED)) + " AND "
                            + keys.compare(EventItems.GSI1SK, ">", s(nowKey)))
                    .expressionAttributeNames(keys.names())
                    .expressionAttributeValues(keys.values())
                    .build();
            return table.queryUpTo("AP-004 list enabled Events", request, limit + 1, start).map(collected -> {
                List<Map<String, AttributeValue>> items = collected.items();
                List<Event> events = items.stream().limit(limit).map(EventItems::event).toList();
                String next = items.size() > limit
                        ? Cursors.events(Attributes.string(items.get(limit - 1), EventItems.GSI1SK))
                        : null;
                return new EnabledEventPage(events, next);
            });
        });
    }

    /** AP-024 take lease: {@code PROVISIONING} and (no lease, expired lease or own lease). */
    @Override
    public Mono<Boolean> acquireProvisioningLease(String eventId, String owner, Instant leaseUntil, Instant now) {
        return Mono.defer(() -> {
            Expression update = new Expression()
                    .set(EventItems.LEASE_OWNER, s(owner))
                    .set(EventItems.LEASE_UNTIL_MS, millis(leaseUntil));
            update.condition(update.eq(EventItems.STATUS, s(ProvisioningStatus.PROVISIONING.name())))
                    .condition(Expression.or(
                            update.notExists(EventItems.LEASE_OWNER),
                            update.compare(EventItems.LEASE_UNTIL_MS, "<", millis(now)),
                            update.eq(EventItems.LEASE_OWNER, s(owner))));
            return table.conditionalUpdate("AP-024 take lease", Keys.meta(Keys.event(eventId)), update);
        });
    }

    /** AP-024 check and record progress before a batch: {@code PROVISIONING} and own lease. */
    @Override
    public Mono<Boolean> recordProvisioningProgress(String eventId, String owner, Instant leaseUntil,
            int provisionedBatches, Instant now) {
        return Mono.defer(() -> {
            Expression update = new Expression()
                    .set(EventItems.LEASE_UNTIL_MS, millis(leaseUntil))
                    .set(EventItems.PROVISIONED_BATCHES, n(provisionedBatches))
                    .set(EventItems.LAST_PROGRESS_AT_MS, millis(now));
            update.condition(update.eq(EventItems.STATUS, s(ProvisioningStatus.PROVISIONING.name())))
                    .condition(update.eq(EventItems.LEASE_OWNER, s(owner)));
            return table.conditionalUpdate("AP-024 record progress", Keys.meta(Keys.event(eventId)), update);
        });
    }

    /** AP-003: Event {@code ENABLED} (reindexed, without lease) and audit {@code EVENT_ENABLED} (2 items). */
    @Override
    public Mono<TransactionOutcome> enable(EnablementPlan plan) {
        return Mono.defer(() -> {
            Event enabled = plan.enabled();
            Expression update = new Expression()
                    .set(EventItems.STATUS, s(ProvisioningStatus.ENABLED.name()))
                    .set(EventItems.ENABLED_AT, instant(plan.enabledAt()))
                    .set(EventItems.GSI1PK, s(Keys.EVENTS_ENABLED))
                    .set(EventItems.GSI1SK, s(Keys.eventLifecycleSort(enabled.startsAt(), enabled.eventId())))
                    .remove(EventItems.LEASE_OWNER, EventItems.LEASE_UNTIL_MS);
            update.condition(update.eq(EventItems.STATUS, s(ProvisioningStatus.PROVISIONING.name())))
                    .condition(update.eq(EventItems.LEASE_OWNER, s(plan.leaseOwner())));
            return table.transact("AP-003 enable Event", List.of(
                            table.update(Keys.meta(Keys.event(enabled.eventId())), update, ItemRole.of(FailedItem.EVENT)),
                            table.insert(AuditItems.item(plan.audit()), ItemRole.of(FailedItem.AUDIT))))
                    .doOnNext(outcome -> {
                        if (outcome instanceof TransactionOutcome.Applied) {
                            cache.remember(enabled);
                        }
                    });
        });
    }

    /** AP-026: Event {@code FAILED} (reindexed, without lease) and audit {@code EVENT_PROVISIONING_FAILED} (2 items). */
    @Override
    public Mono<TransactionOutcome> markFailed(ProvisioningFailurePlan plan) {
        return Mono.defer(() -> {
            String eventId = plan.failed().eventId();
            Expression update = new Expression()
                    .set(EventItems.STATUS, s(ProvisioningStatus.FAILED.name()))
                    .set(EventItems.FAILED_AT, instant(plan.failedAt()))
                    .set(EventItems.FAILURE_REASON, s(EventItems.PROVISIONING_FAILED))
                    .set(EventItems.GSI1PK, s(Keys.EVENTS_FAILED))
                    .set(EventItems.GSI1SK, s(Keys.eventLifecycleSort(plan.failedAt(), eventId)))
                    .remove(EventItems.LEASE_OWNER, EventItems.LEASE_UNTIL_MS);
            update.condition(update.eq(EventItems.STATUS, s(ProvisioningStatus.PROVISIONING.name())));
            return table.transact("AP-026 mark Event FAILED", List.of(
                    table.update(Keys.meta(Keys.event(eventId)), update, ItemRole.of(FailedItem.EVENT)),
                    table.insert(AuditItems.item(plan.audit()), ItemRole.of(FailedItem.AUDIT))));
        });
    }

    /** AP-018: {@code GSI1PK = EVENTS#PROVISIONING} filtered by {@code lastProgressAtMs < progressBefore}. */
    @Override
    public Flux<StalledProvisioning> findStalledProvisioning(Instant progressBefore) {
        return Flux.defer(() -> {
            Expression expression = new Expression();
            QueryRequest request = QueryRequest.builder()
                    .tableName(table.name())
                    .indexName("GSI1")
                    .keyConditionExpression(expression.eq(EventItems.GSI1PK, s(Keys.EVENTS_PROVISIONING)))
                    .filterExpression(expression.compare(EventItems.LAST_PROGRESS_AT_MS, "<", millis(progressBefore)))
                    .expressionAttributeNames(expression.names())
                    .expressionAttributeValues(expression.values())
                    .build();
            return table.queryAll("AP-018 stalled provisioning", request).map(EventItems::stalled);
        });
    }

    /** AP-033: {@code provisioningRepublishCount + 1}, progress now; {@code PROVISIONING} and same progress. */
    @Override
    public Mono<Boolean> registerRepublication(String eventId, Instant expectedLastProgressAt, Instant now) {
        return Mono.defer(() -> {
            Expression update = new Expression()
                    .increment(EventItems.REPUBLISH_COUNT, 1)
                    .set(EventItems.LAST_PROGRESS_AT_MS, millis(now));
            update.condition(update.eq(EventItems.STATUS, s(ProvisioningStatus.PROVISIONING.name())))
                    .condition(update.eq(EventItems.LAST_PROGRESS_AT_MS, millis(expectedLastProgressAt)));
            return table.conditionalUpdate("AP-033 register republication", Keys.meta(Keys.event(eventId)), update);
        });
    }

    /** AP-027 query: {@code GSI1PK = EVENTS#FAILED} (Events leave the range once purged). */
    @Override
    public Flux<String> findFailedPendingPurge() {
        return Flux.defer(() -> {
            Expression expression = new Expression();
            QueryRequest request = QueryRequest.builder()
                    .tableName(table.name())
                    .indexName("GSI1")
                    .keyConditionExpression(expression.eq(EventItems.GSI1PK, s(Keys.EVENTS_FAILED)))
                    .expressionAttributeNames(expression.names())
                    .expressionAttributeValues(expression.values())
                    .build();
            return table.queryAll("AP-027 failed Events", request)
                    .map(item -> Attributes.string(item, EventItems.EVENT_ID));
        });
    }

    /** AP-027 final mark: {@code ticketsPurgedAt}, out of {@code GSI1}; condition {@code FAILED}. */
    @Override
    public Mono<Boolean> markTicketsPurged(String eventId, Instant purgedAt) {
        return Mono.defer(() -> {
            Expression update = new Expression()
                    .set(EventItems.TICKETS_PURGED_AT, instant(purgedAt))
                    .remove(EventItems.GSI1PK, EventItems.GSI1SK);
            update.condition(update.eq(EventItems.STATUS, s(ProvisioningStatus.FAILED.name())));
            return table.conditionalUpdate("AP-027 mark purged", Keys.meta(Keys.event(eventId)), update);
        });
    }
}
