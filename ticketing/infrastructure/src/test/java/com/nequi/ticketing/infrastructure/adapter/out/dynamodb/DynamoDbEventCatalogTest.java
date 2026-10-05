package com.nequi.ticketing.infrastructure.adapter.out.dynamodb;

import static com.nequi.ticketing.infrastructure.adapter.out.dynamodb.PersistencePortContract.DEFINITION;
import static com.nequi.ticketing.infrastructure.adapter.out.dynamodb.PersistencePortContract.NOW;
import static com.nequi.ticketing.infrastructure.adapter.out.dynamodb.PersistencePortContract.eventAudit;
import static com.nequi.ticketing.infrastructure.adapter.out.dynamodb.Requests.done;
import static com.nequi.ticketing.infrastructure.adapter.out.dynamodb.Requests.render;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nequi.ticketing.application.port.out.EnabledEventPage;
import com.nequi.ticketing.application.port.out.EnablementPlan;
import com.nequi.ticketing.application.port.out.NewEventPlan;
import com.nequi.ticketing.application.port.out.ProvisioningFailurePlan;
import com.nequi.ticketing.application.port.out.ProvisioningSnapshot;
import com.nequi.ticketing.application.port.out.StalledProvisioning;
import com.nequi.ticketing.domain.audit.AuditRecord.AuditCode;
import com.nequi.ticketing.domain.error.ValidationException;
import com.nequi.ticketing.domain.event.Event;
import com.nequi.ticketing.domain.event.Event.ProvisioningStatus;
import com.nequi.ticketing.domain.event.InventoryLimits;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

/** Unit tests of the Event catalog of the DynamoDB adapter with a simulated client (ADR-024, ADR-038, ADR-040). */
class DynamoDbEventCatalogTest {

    private static final String EVENT_ID = "e0000000-0000-4000-8000-000000000001";
    private static final Event EVENT = Event.create(EVENT_ID, "Concert", "Arena", NOW.plus(Duration.ofDays(10)), 25,
            DEFINITION, NOW, InventoryLimits.DEPLOYED);

    private DynamoDbAsyncClient client;
    private DynamoDbEventCatalog catalog;

    @BeforeEach
    void setUp() {
        client = Requests.client();
        catalog = Requests.persistence(client).eventCatalog();
        Requests.transactionsApply(client);
        when(client.updateItem(any(UpdateItemRequest.class))).thenReturn(done(UpdateItemResponse.builder().build()));
    }

    @Test
    @DisplayName("AP-001 addendum creating an Event is one transaction of 3 inserts; the Event starts in PROVISIONING with progress at creation")
    void create() {
        NewEventPlan plan = PersistencePortContract.newEventPlan(EVENT, NOW);
        catalog.create(plan).block();

        TransactWriteItemsRequest request = lastTransaction();
        assertThat(render(request)).containsExactly(
                "Put EVENT#" + EVENT_ID + " #META | IF attribute_not_exists(PK)",
                "Put IDEMEVT#admin#" + plan.idempotency().idempotencyKey() + " #META | IF attribute_not_exists(PK)",
                "Put EVENT#" + EVENT_ID + " " + Keys.auditSort(plan.audit()) + " | IF attribute_not_exists(PK)");
        Map<String, AttributeValue> event = request.transactItems().getFirst().put().item();
        assertThat(event.get("provisioningStatus").s()).isEqualTo("PROVISIONING");
        assertThat(event.get("GSI1PK").s()).isEqualTo("EVENTS#PROVISIONING");
        assertThat(event.get("GSI1SK").s()).isEqualTo(Keys.sortable(NOW) + "#" + EVENT_ID);
        assertThat(event.get("lastProgressAtMs").n()).isEqualTo(Long.toString(NOW.toEpochMilli()));
        assertThat(event.get("availabilityShards").n()).isEqualTo("1");
        assertThat(event.get("totalBatches").n()).isEqualTo("1");
        assertThat(event.get("availableAtCreation").n()).isEqualTo("24");
        assertThat(event.get("complimentaryCount").n()).isEqualTo("1");
        assertThat(request.transactItems().get(1).put().item().get("ttl").n())
                .isEqualTo(Long.toString(NOW.plus(Duration.ofHours(24)).getEpochSecond()));
    }

    @Test
    @DisplayName("AP-006 ADR-023 an ENABLED Event is cached after its first eventually consistent read; other statuses are always read")
    void findEventCachesEnabledEvents() {
        Map<String, AttributeValue> provisioning = EventItems.newEvent(PersistencePortContract.newEventPlan(EVENT, NOW));
        Map<String, AttributeValue> enabled = new HashMap<>(provisioning);
        enabled.put("provisioningStatus", AttributeValue.fromS("ENABLED"));
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(done(GetItemResponse.builder().item(provisioning).build()))
                .thenReturn(done(GetItemResponse.builder().item(enabled).build()))
                .thenReturn(done(GetItemResponse.builder().build()));

        assertThat(catalog.findEvent(EVENT_ID).block()).isEqualTo(EVENT);
        assertThat(catalog.findEvent(EVENT_ID).block().provisioningStatus()).isEqualTo(ProvisioningStatus.ENABLED);
        assertThat(catalog.findEvent(EVENT_ID).block().provisioningStatus()).isEqualTo(ProvisioningStatus.ENABLED);
        ArgumentCaptor<GetItemRequest> reads = ArgumentCaptor.forClass(GetItemRequest.class);
        verify(client, times(2)).getItem(reads.capture());
        assertThat(reads.getValue().consistentRead()).isFalse();
        assertThat(catalog.findEvent("other").blockOptional()).isEmpty();
    }

    @Test
    @DisplayName("AP-023 the provisioning snapshot is a strongly consistent read with lease and progress")
    void snapshot() {
        Map<String, AttributeValue> item = new HashMap<>(EventItems.newEvent(PersistencePortContract.newEventPlan(EVENT, NOW)));
        item.put("provisioningLeaseOwner", AttributeValue.fromS("w/1"));
        item.put("provisioningLeaseUntilMs", AttributeValue.fromN(Long.toString(NOW.plusSeconds(60).toEpochMilli())));
        item.put("provisionedBatches", AttributeValue.fromN("1"));
        when(client.getItem(any(GetItemRequest.class))).thenReturn(done(GetItemResponse.builder().item(item).build()));

        ProvisioningSnapshot snapshot = catalog.findProvisioningSnapshot(EVENT_ID).block();
        assertThat(snapshot).isEqualTo(new ProvisioningSnapshot(EVENT, NOW, 1, null, null, "w/1", NOW.plusSeconds(60), NOW, 0,
                null));
        ArgumentCaptor<GetItemRequest> read = ArgumentCaptor.forClass(GetItemRequest.class);
        verify(client).getItem(read.capture());
        assertThat(read.getValue().consistentRead()).isTrue();
    }

    @Test
    @DisplayName("AP-004 BR-022 the listing queries GSI1 for ENABLED Events strictly after now, reads one extra item and resumes from its cursor")
    void listing() {
        Map<String, AttributeValue> first = projection("e-1", NOW.plusSeconds(10));
        Map<String, AttributeValue> second = projection("e-2", NOW.plusSeconds(20));
        Map<String, AttributeValue> third = projection("e-3", NOW.plusSeconds(30));
        when(client.query(any(QueryRequest.class)))
                .thenReturn(done(QueryResponse.builder().items(first, second, third).count(3)
                        .lastEvaluatedKey(Map.of("PK", AttributeValue.fromS("x"))).build()))
                .thenReturn(done(QueryResponse.builder().items(third).count(1).build()));

        EnabledEventPage page = catalog.listEnabledUpcoming(NOW, 2, null).block();
        assertThat(page.events()).extracting(Event::eventId).containsExactly("e-1", "e-2");
        assertThat(page.events().getFirst().inventoryDefinition().sections()).isEmpty();
        assertThat(page.nextCursor()).isEqualTo(Cursors.events(Keys.eventLifecycleSort(NOW.plusSeconds(20), "e-2")));
        EnabledEventPage next = catalog.listEnabledUpcoming(NOW, 2, page.nextCursor()).block();
        assertThat(next.events()).extracting(Event::eventId).containsExactly("e-3");
        assertThat(next.nextCursor()).isNull();

        ArgumentCaptor<QueryRequest> queries = ArgumentCaptor.forClass(QueryRequest.class);
        verify(client, times(2)).query(queries.capture());
        assertThat(render(queries.getAllValues().getFirst())).isEqualTo("GSI1 | GSI1PK = 'EVENTS#ENABLED' AND GSI1SK > '"
                + Keys.sortable(NOW) + "#~'");
        assertThat(queries.getAllValues().getFirst().limit()).isEqualTo(3);
        assertThat(queries.getAllValues().getFirst().exclusiveStartKey()).isEmpty();
        assertThat(queries.getAllValues().get(1).exclusiveStartKey()).containsEntry("PK", AttributeValue.fromS("EVENT#e-2"))
                .containsEntry("GSI1SK", AttributeValue.fromS(Keys.eventLifecycleSort(NOW.plusSeconds(20), "e-2")));

        catalog.listEnabledUpcoming(NOW.plusSeconds(25), 2, page.nextCursor()).block();
        verify(client, times(3)).query(queries.capture());
        assertThat(queries.getValue().exclusiveStartKey()).as("a cursor at or before now is outside the range").isEmpty();
        assertThatThrownBy(() -> catalog.listEnabledUpcoming(NOW, 2, Cursors.events("not-a-key")).block())
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("AP-024 AP-033 AP-027 the lease, progress, republication and purge marks are conditional updates with the data model conditions")
    void conditionalUpdates() {
        long now = NOW.toEpochMilli();
        catalog.acquireProvisioningLease(EVENT_ID, "w/1", NOW.plusSeconds(60), NOW).block();
        assertThat(render(lastUpdate())).isEqualTo("Update EVENT#" + EVENT_ID + " #META | SET provisioningLeaseOwner = 'w/1', "
                + "provisioningLeaseUntilMs = " + (now + 60_000) + " | IF provisioningStatus = 'PROVISIONING' AND "
                + "(attribute_not_exists(provisioningLeaseOwner) OR provisioningLeaseUntilMs < " + now
                + " OR provisioningLeaseOwner = 'w/1')");
        catalog.recordProvisioningProgress(EVENT_ID, "w/1", NOW.plusSeconds(90), 3, NOW.plusSeconds(30)).block();
        assertThat(render(lastUpdate())).isEqualTo("Update EVENT#" + EVENT_ID + " #META | SET provisioningLeaseUntilMs = "
                + (now + 90_000) + ", provisionedBatches = 3, lastProgressAtMs = " + (now + 30_000)
                + " | IF provisioningStatus = 'PROVISIONING' AND provisioningLeaseOwner = 'w/1'");
        catalog.registerRepublication(EVENT_ID, NOW, NOW.plusSeconds(200)).block();
        assertThat(render(lastUpdate())).isEqualTo("Update EVENT#" + EVENT_ID + " #META | SET provisioningRepublishCount = "
                + "provisioningRepublishCount + 1, lastProgressAtMs = " + (now + 200_000)
                + " | IF provisioningStatus = 'PROVISIONING' AND lastProgressAtMs = " + now);
        catalog.markTicketsPurged(EVENT_ID, NOW.plusSeconds(300)).block();
        assertThat(render(lastUpdate())).isEqualTo("Update EVENT#" + EVENT_ID + " #META | SET ticketsPurgedAt = '"
                + NOW.plusSeconds(300) + "' REMOVE GSI1PK, GSI1SK | IF provisioningStatus = 'FAILED'");
    }

    @Test
    @DisplayName("AP-003 AP-026 enabling and failing are 2-item transactions that reindex GSI1 and drop the lease")
    void enableAndFail() {
        Event enabled = EVENT.enable(25);
        catalog.enable(new EnablementPlan(EVENT, enabled, "w/1", eventAudit(AuditCode.EVENT_ENABLED, EVENT_ID, NOW), NOW))
                .block();
        assertThat(render(lastTransaction())).hasSize(2).first().asString().isEqualTo("Update EVENT#" + EVENT_ID
                + " #META | SET provisioningStatus = 'ENABLED', enabledAt = '" + NOW + "', GSI1PK = 'EVENTS#ENABLED', GSI1SK = '"
                + Keys.eventLifecycleSort(EVENT.startsAt(), EVENT_ID) + "' REMOVE provisioningLeaseOwner, "
                + "provisioningLeaseUntilMs | IF provisioningStatus = 'PROVISIONING' AND provisioningLeaseOwner = 'w/1'");
        assertThat(catalog.findEvent(EVENT_ID).block()).as("cached after enabling").isEqualTo(enabled);

        catalog.markFailed(new ProvisioningFailurePlan(EVENT, EVENT.fail(),
                eventAudit(AuditCode.EVENT_PROVISIONING_FAILED, EVENT_ID, NOW), NOW.plusSeconds(5))).block();
        assertThat(render(lastTransaction())).hasSize(2).first().asString().isEqualTo("Update EVENT#" + EVENT_ID
                + " #META | SET provisioningStatus = 'FAILED', failedAt = '" + NOW.plusSeconds(5)
                + "', provisioningFailureReason = 'PROVISIONING_FAILED', GSI1PK = 'EVENTS#FAILED', GSI1SK = '"
                + Keys.eventLifecycleSort(NOW.plusSeconds(5), EVENT_ID) + "' REMOVE provisioningLeaseOwner, "
                + "provisioningLeaseUntilMs | IF provisioningStatus = 'PROVISIONING'");
    }

    @Test
    @DisplayName("AP-018 AP-027 stalled and failed Events are read from GSI1, following every page")
    void indexQueries() {
        Map<String, AttributeValue> stalled = projection("e-9", NOW.plusSeconds(10));
        when(client.query(any(QueryRequest.class)))
                .thenReturn(done(QueryResponse.builder().items(stalled).count(1)
                        .lastEvaluatedKey(Map.of("PK", AttributeValue.fromS("EVENT#e-9"))).build()))
                .thenReturn(done(QueryResponse.builder().items(List.of()).count(0).build()))
                .thenReturn(done(QueryResponse.builder().items(stalled).count(1).build()));

        assertThat(catalog.findStalledProvisioning(NOW.plusSeconds(1)).collectList().block())
                .containsExactly(new StalledProvisioning("e-9", NOW, 2));
        assertThat(catalog.findFailedPendingPurge().collectList().block()).containsExactly("e-9");
        ArgumentCaptor<QueryRequest> queries = ArgumentCaptor.forClass(QueryRequest.class);
        verify(client, times(3)).query(queries.capture());
        assertThat(render(queries.getAllValues().getFirst())).isEqualTo("GSI1 | GSI1PK = 'EVENTS#PROVISIONING' | FILTER "
                + "lastProgressAtMs < " + NOW.plusSeconds(1).toEpochMilli());
        assertThat(queries.getAllValues().get(1).exclusiveStartKey()).containsEntry("PK", AttributeValue.fromS("EVENT#e-9"));
        assertThat(render(queries.getAllValues().get(2))).isEqualTo("GSI1 | GSI1PK = 'EVENTS#FAILED'");
    }

    private static Map<String, AttributeValue> projection(String eventId, java.time.Instant startsAt) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("PK", AttributeValue.fromS("EVENT#" + eventId));
        item.put("SK", AttributeValue.fromS("#META"));
        item.put("eventId", AttributeValue.fromS(eventId));
        item.put("name", AttributeValue.fromS("Concert " + eventId));
        item.put("venue", AttributeValue.fromS("Arena"));
        item.put("startsAt", AttributeValue.fromS(startsAt.toString()));
        item.put("capacity", AttributeValue.fromN("25"));
        item.put("availabilityShards", AttributeValue.fromN("1"));
        item.put("provisioningStatus", AttributeValue.fromS("ENABLED"));
        item.put("lastProgressAtMs", AttributeValue.fromN(Long.toString(NOW.toEpochMilli())));
        item.put("provisioningRepublishCount", AttributeValue.fromN("2"));
        item.put("GSI1SK", AttributeValue.fromS(Keys.eventLifecycleSort(startsAt, eventId)));
        return item;
    }

    private TransactWriteItemsRequest lastTransaction() {
        ArgumentCaptor<TransactWriteItemsRequest> captor = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(client, atLeastOnce()).transactWriteItems(captor.capture());
        return captor.getValue();
    }

    private UpdateItemRequest lastUpdate() {
        ArgumentCaptor<UpdateItemRequest> captor = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(client, atLeastOnce()).updateItem(captor.capture());
        return captor.getValue();
    }
}
