package com.nequi.ticketing.infrastructure.adapter.out.dynamodb;

import static com.nequi.ticketing.infrastructure.adapter.out.dynamodb.PersistencePortContract.NOW;
import static com.nequi.ticketing.infrastructure.adapter.out.dynamodb.Requests.done;
import static com.nequi.ticketing.infrastructure.adapter.out.dynamodb.Requests.failed;
import static com.nequi.ticketing.infrastructure.adapter.out.dynamodb.Requests.render;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nequi.ticketing.application.port.out.IdempotencyRecord;
import com.nequi.ticketing.application.port.out.OrderRecord;
import com.nequi.ticketing.application.port.out.PaymentLease;
import com.nequi.ticketing.domain.audit.AuditRecord;
import com.nequi.ticketing.domain.audit.AuditRecord.AuditCode;
import com.nequi.ticketing.domain.event.ShardingPolicy;
import com.nequi.ticketing.domain.order.Order;
import com.nequi.ticketing.domain.order.PurchaseRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;

/** Unit tests of the reads of the DynamoDB adapter: AP-009, AP-010, AP-016, AP-017, AP-022, AP-028, AP-029 (ADR-038). */
class DynamoDbReadsTest {

    private DynamoDbAsyncClient client;
    private DynamoDbPersistence persistence;

    @BeforeEach
    void setUp() {
        client = Requests.client();
        persistence = Requests.persistence(client);
    }

    @Test
    @DisplayName("AP-010 AC-006 ADR-025 the Order item is read with strong consistency and mapped with its technical attributes")
    void findOrder() {
        Order order = Order.create("o-1", "customer-1", new PurchaseRequest("e-1", List.of("A-1-1"), "purchase-key-00000001"),
                NOW).startPayment(NOW.plusSeconds(5)).failProcessing(NOW.plusSeconds(9))
                .rescheduleReversal(NOW.plusSeconds(10));
        Map<String, AttributeValue> item = new HashMap<>(OrderItems.newOrder(Order.create("o-1", "customer-1",
                new PurchaseRequest("e-1", List.of("A-1-1"), "purchase-key-00000001"), NOW), ShardingPolicy.DEPLOYED));
        item.put("status", AttributeValue.fromS("FAILED"));
        item.put("failureCause", AttributeValue.fromS("PROCESSING_FAILED"));
        item.put("updatedAt", AttributeValue.fromS(NOW.plusSeconds(9).toString()));
        item.put("enqueuedAt", AttributeValue.fromS(NOW.plusSeconds(1).toString()));
        item.put("paymentAttemptId", AttributeValue.fromS("o-1-1"));
        item.put("paymentOutcome", AttributeValue.fromS("UNKNOWN"));
        item.put("paymentStartedAt", AttributeValue.fromS(NOW.plusSeconds(5).toString()));
        item.put("paymentLeaseOwner", AttributeValue.fromS("w/1"));
        item.put("paymentLeaseUntilMs", AttributeValue.fromN(Long.toString(NOW.plusSeconds(50).toEpochMilli())));
        item.put("paymentReversalPending", AttributeValue.fromBool(true));
        item.put("paymentReversalRequestedAt", AttributeValue.fromS(NOW.plusSeconds(9).toString()));
        item.put("paymentReversalAttempts", AttributeValue.fromN("1"));
        item.put("paymentReversalNextAttemptAtMs", AttributeValue.fromN(Long.toString(NOW.plusSeconds(20).toEpochMilli())));
        Order fresh = Order.create("o-4", "customer-1", new PurchaseRequest("e-1", List.of("A-1-2"), "purchase-key-00000002"),
                NOW);
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(done(GetItemResponse.builder().item(item).build()))
                .thenReturn(done(GetItemResponse.builder().build()))
                .thenReturn(done(GetItemResponse.builder().item(OrderItems.newOrder(fresh, ShardingPolicy.DEPLOYED)).build()))
                .thenReturn(failed(ResourceNotFoundException.builder().message("no table").build()));

        assertThat(persistence.orderReader().findById("o-1").block()).isEqualTo(new OrderRecord(order, NOW,
                NOW.plusSeconds(9), NOW.plusSeconds(1), new PaymentLease("w/1", NOW.plusSeconds(50))));
        assertThat(persistence.orderReader().findById("o-2").blockOptional()).isEmpty();
        assertThat(persistence.orderReader().findById("o-4").block()).isEqualTo(new OrderRecord(fresh, NOW, NOW, null, null));
        StepVerifier.create(persistence.orderReader().findById("o-3")).expectError(DynamoDbStoreException.class).verify();
        ArgumentCaptor<GetItemRequest> reads = ArgumentCaptor.forClass(GetItemRequest.class);
        verify(client, times(4)).getItem(reads.capture());
        assertThat(reads.getAllValues().getFirst().consistentRead()).isTrue();
        assertThat(reads.getAllValues().getFirst().key().get("PK").s()).isEqualTo("ORDER#o-1");
    }

    @Test
    @DisplayName("AP-016 AP-028 AP-029 the work-index queries use the shard partitions and the time bounds of the data model")
    void workIndexQueries() {
        when(client.query(any(QueryRequest.class))).thenReturn(done(QueryResponse.builder()
                .items(Map.of("PK", AttributeValue.fromS("ORDER#o-7"), "SK", AttributeValue.fromS("#META"))).count(1).build()));
        Instant at = Instant.ofEpochMilli(1_793_541_600_000L);

        assertThat(persistence.orderReader().findDueReservations(3, at).collectList().block()).containsExactly("o-7");
        assertThat(persistence.orderReader().findPendingEnqueue(5, at).collectList().block()).containsExactly("o-7");
        assertThat(persistence.orderReader().findDueReversals(1, at).collectList().block()).containsExactly("o-7");
        ArgumentCaptor<QueryRequest> queries = ArgumentCaptor.forClass(QueryRequest.class);
        verify(client, times(3)).query(queries.capture());
        assertThat(queries.getAllValues()).extracting(Requests::render).containsExactly(
                "GSI3 | GSI3PK = 'RESV#3' AND GSI3SK <= '1793541600000~'",
                "GSI4 | GSI4PK = 'PENDQ#5' AND GSI4SK < '1793541600000'",
                "GSI3 | GSI3PK = 'REVERSAL#1' AND GSI3SK <= '1793541600000~'");
    }

    @Test
    @DisplayName("AP-009 AP-022 ADR-027 idempotency records are strongly consistent reads whose validity comes from the TTL attribute")
    void idempotency() {
        Map<String, AttributeValue> purchase = IdempotencyItems.purchase(new IdempotencyRecord("customer-1", "key-0000000000000001",
                "o-1", "hash", NOW, NOW.plus(Duration.ofHours(24)).plusMillis(1)));
        Map<String, AttributeValue> creation = IdempotencyItems.eventCreation(new IdempotencyRecord("admin",
                "key-0000000000000002", "e-1", "hash-2", NOW, NOW.plus(Duration.ofHours(24))));
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(done(GetItemResponse.builder().item(purchase).build()))
                .thenReturn(done(GetItemResponse.builder().item(creation).build()));

        IdempotencyRecord found = persistence.idempotencyStore().findPurchase("customer-1", "key-0000000000000001").block();
        assertThat(found.resourceId()).isEqualTo("o-1");
        assertThat(found.expiresAt()).as("rounded up, never shorter than the approved validity")
                .isEqualTo(NOW.plus(Duration.ofHours(24)).plusSeconds(1));
        assertThat(persistence.idempotencyStore().findEventCreation("admin", "key-0000000000000002").block())
                .isEqualTo(new IdempotencyRecord("admin", "key-0000000000000002", "e-1", "hash-2", NOW,
                        NOW.plus(Duration.ofHours(24))));
        ArgumentCaptor<GetItemRequest> reads = ArgumentCaptor.forClass(GetItemRequest.class);
        verify(client, times(2)).getItem(reads.capture());
        assertThat(reads.getAllValues()).allMatch(GetItemRequest::consistentRead);
        assertThat(reads.getAllValues().get(1).key().get("PK").s()).isEqualTo("IDEMEVT#admin#key-0000000000000002");
        assertThat(purchase.get("entityType").s()).isEqualTo("PURCHASE_IDEMPOTENCY");
    }

    @Test
    @DisplayName("AP-017 AC-015 IV-013 the audit trail is read from the Order or Event collection, with included causes and Event counts")
    void auditTrail() {
        AuditRecord orderAudit = PersistencePortContract.audit(AuditCode.RESERVATION_EXPIRED,
                Order.create("o-1", "customer-1", new PurchaseRequest("e-1", List.of("A-1-1"), "purchase-key-00000001"), NOW)
                        .expire(NOW.plus(Order.RESERVATION_DURATION)), NOW, AuditCode.PAYMENT_REVERSAL_REQUESTED);
        AuditRecord eventAudit = PersistencePortContract.eventAudit(AuditCode.EVENT_ENABLED, "e-1", NOW);
        Map<String, AttributeValue> eventItem = AuditItems.item(eventAudit);
        assertThat(eventItem).doesNotContainKeys("transitionIds", "ticketIds", "orderFrom", "includedCauses");
        assertThat(AuditItems.item(orderAudit).get("includedCauses").l()).containsExactly(
                AttributeValue.fromS("PAYMENT_REVERSAL_REQUESTED"));
        when(client.query(any(QueryRequest.class)))
                .thenReturn(done(QueryResponse.builder().items(AuditItems.item(orderAudit)).count(1).build()))
                .thenReturn(done(QueryResponse.builder().items(eventItem).count(1).build()));

        assertThat(persistence.auditTrail().orderAudit("o-1").collectList().block()).containsExactly(orderAudit);
        AuditRecord readEvent = persistence.auditTrail().eventAudit("e-1").blockFirst();
        assertThat(readEvent.code()).isEqualTo(AuditCode.EVENT_ENABLED);
        assertThat(readEvent.orderId()).isNull();
        assertThat(readEvent.availableCount()).isEqualTo(24);
        assertThat(readEvent.complimentaryCount()).isEqualTo(1);
        assertThat(readEvent.actor()).isEqualTo(eventAudit.actor());
        ArgumentCaptor<QueryRequest> queries = ArgumentCaptor.forClass(QueryRequest.class);
        verify(client, times(2)).query(queries.capture());
        assertThat(render(queries.getAllValues().getFirst())).isEqualTo("table | PK = 'ORDER#o-1' AND begins_with(SK, 'AUDIT#')");
        assertThat(queries.getAllValues().getFirst().consistentRead()).isTrue();
        assertThat(render(queries.getAllValues().get(1))).isEqualTo("table | PK = 'EVENT#e-1' AND begins_with(SK, 'AUDIT#')");
    }
}
