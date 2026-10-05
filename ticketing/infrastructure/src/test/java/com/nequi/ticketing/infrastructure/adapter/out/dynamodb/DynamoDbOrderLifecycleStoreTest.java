package com.nequi.ticketing.infrastructure.adapter.out.dynamodb;

import static com.nequi.ticketing.infrastructure.adapter.out.dynamodb.PersistencePortContract.DEFINITION;
import static com.nequi.ticketing.infrastructure.adapter.out.dynamodb.PersistencePortContract.NOW;
import static com.nequi.ticketing.infrastructure.adapter.out.dynamodb.PersistencePortContract.audit;
import static com.nequi.ticketing.infrastructure.adapter.out.dynamodb.Requests.cancelled;
import static com.nequi.ticketing.infrastructure.adapter.out.dynamodb.Requests.code;
import static com.nequi.ticketing.infrastructure.adapter.out.dynamodb.Requests.conditionFailed;
import static com.nequi.ticketing.infrastructure.adapter.out.dynamodb.Requests.done;
import static com.nequi.ticketing.infrastructure.adapter.out.dynamodb.Requests.failed;
import static com.nequi.ticketing.infrastructure.adapter.out.dynamodb.Requests.none;
import static com.nequi.ticketing.infrastructure.adapter.out.dynamodb.Requests.render;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nequi.ticketing.application.port.out.ClosurePlan;
import com.nequi.ticketing.application.port.out.ConfirmationPlan;
import com.nequi.ticketing.application.port.out.EnqueueFailurePlan;
import com.nequi.ticketing.application.port.out.FailedItem;
import com.nequi.ticketing.application.port.out.ItemFailure;
import com.nequi.ticketing.application.port.out.LateApprovalPlan;
import com.nequi.ticketing.application.port.out.PaymentLease;
import com.nequi.ticketing.application.port.out.PaymentStartPlan;
import com.nequi.ticketing.application.port.out.QuarantinePlan;
import com.nequi.ticketing.application.port.out.ReservationPlan;
import com.nequi.ticketing.application.port.out.ReversalCompletionPlan;
import com.nequi.ticketing.application.port.out.ReversalExhaustionPlan;
import com.nequi.ticketing.application.port.out.TransactionOutcome;
import com.nequi.ticketing.domain.audit.AuditRecord.AuditCode;
import com.nequi.ticketing.domain.event.Event;
import com.nequi.ticketing.domain.event.ShardingPolicy;
import com.nequi.ticketing.domain.order.Order;
import com.nequi.ticketing.domain.order.Order.PaymentOutcome;
import com.nequi.ticketing.domain.order.PurchaseRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException;
import software.amazon.awssdk.services.dynamodb.model.ReturnValuesOnConditionCheckFailure;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

/**
 * Unit tests of the Order transitions of the DynamoDB adapter with a simulated client (ADR-038): the exact
 * items, keys, updates and conditions of {@code ticketing.data-model.v2.md} §5, the transaction sizes of the
 * consolidation addendum (14 / 13 / 12 / 2), the reason per item and the conflict retry (ADR-023, ADR-039).
 */
class DynamoDbOrderLifecycleStoreTest {

    private static final String EVENT_ID = "e0000000-0000-4000-8000-000000000001";
    private static final String ORDER_ID = "o0000000-0000-4000-8000-000000000001";
    private static final List<String> TEN = IntStream.rangeClosed(1, 10).mapToObj(seat -> "A-1-" + seat).toList();
    private static final Event EVENT = Event.create(EVENT_ID, "Concert", "Arena", NOW.plus(Duration.ofDays(10)), 25,
            DEFINITION, NOW, com.nequi.ticketing.domain.event.InventoryLimits.DEPLOYED).enable(25);

    private DynamoDbAsyncClient client;
    private DynamoDbOrderLifecycleStore store;
    private Order order;

    @BeforeEach
    void setUp() {
        client = Requests.client();
        store = Requests.persistence(client).orderLifecycleStore();
        order = Order.create(ORDER_ID, "customer-1", new PurchaseRequest(EVENT_ID, TEN, "purchase-key-00000001"), NOW);
        Map<String, AttributeValue> eventItem = new HashMap<>(EventItems.newEvent(PersistencePortContract.newEventPlan(
                Event.create(EVENT_ID, "Concert", "Arena", NOW.plus(Duration.ofDays(10)), 25, DEFINITION, NOW,
                        com.nequi.ticketing.domain.event.InventoryLimits.DEPLOYED), NOW)));
        eventItem.put(EventItems.STATUS, AttributeValue.fromS("ENABLED"));
        when(client.getItem(any(GetItemRequest.class))).thenReturn(done(GetItemResponse.builder().item(eventItem).build()));
        Requests.transactionsApply(client);
    }

    @Test
    @DisplayName("AP-008 ADR-023 addendum the reservation of 10 Tickets is one transaction of 14 items with exactly the data model conditions")
    void reserveItems() {
        assertThat(store.reserve(plan(order)).block()).isEqualTo(TransactionOutcome.applied());

        TransactWriteItemsRequest request = lastTransaction();
        List<String> lines = render(request);
        assertThat(lines).hasSize(14);
        assertThat(lines.getFirst()).isEqualTo("Update TICKET#" + EVENT_ID + "#A-1-1 #META | SET state = 'RESERVED', orderId = '"
                + ORDER_ID + "', updatedAt = '" + NOW + "' REMOVE GSI2PK, GSI2SK | IF attribute_exists(PK) AND eventId = '"
                + EVENT_ID + "' AND state = 'AVAILABLE'");
        assertThat(lines.subList(10, 14)).containsExactly(
                "Put ORDER#" + ORDER_ID + " #META | IF attribute_not_exists(PK)",
                "Put IDEM#customer-1#" + plan(order).idempotency().idempotencyKey() + " #META | IF attribute_not_exists(PK)",
                "Put ORDER#" + ORDER_ID + " " + Keys.auditSort(plan(order).audit()) + " | IF attribute_not_exists(PK)",
                "Put ACTIVE#customer-1#" + EVENT_ID + " #META | IF attribute_not_exists(PK)");
        Map<String, AttributeValue> orderItem = request.transactItems().get(10).put().item();
        assertThat(orderItem.get("GSI3PK").s()).isEqualTo("RESV#" + ShardingPolicy.shard(ORDER_ID, 8));
        assertThat(orderItem.get("GSI3SK").s()).isEqualTo(Keys.millis13(order.reservation().expiresAt().toEpochMilli())
                + "#" + ORDER_ID);
        assertThat(orderItem.get("GSI4PK").s()).isEqualTo("PENDQ#" + ShardingPolicy.shard(ORDER_ID, 8));
        assertThat(request.transactItems().get(13).put().item().get("orderId").s()).isEqualTo(ORDER_ID);
        assertThat(request.transactItems()).allSatisfy(item -> assertThat(returnValues(item))
                .isEqualTo(ReturnValuesOnConditionCheckFailure.ALL_OLD));
    }

    @Test
    @DisplayName("AP-012 addendum starting the payment of 10 Tickets is one transaction of 12 items with the cutoff condition")
    void startPaymentItems() {
        Order started = order.startPayment(NOW.plusSeconds(5));
        Instant cutoff = NOW.plusSeconds(20);
        store.startPayment(new PaymentStartPlan(order, started, new PaymentLease("w/1", NOW.plusSeconds(50)),
                audit(AuditCode.PAYMENT_STARTED, started, NOW.plusSeconds(5)), NOW.plusSeconds(5), cutoff)).block();

        List<String> lines = render(lastTransaction());
        assertThat(lines).hasSize(12);
        assertThat(lines.getFirst()).isEqualTo("Update ORDER#" + ORDER_ID + " #META | SET paymentAttemptId = '" + ORDER_ID
                + "-1', paymentAttemptNo = 1, paymentStartedAt = '" + NOW.plusSeconds(5) + "', paymentOutcome = 'UNKNOWN', "
                + "paymentLeaseOwner = 'w/1', paymentLeaseUntilMs = " + NOW.plusSeconds(50).toEpochMilli() + ", updatedAt = '"
                + NOW.plusSeconds(5) + "' | IF status = 'CREATED' AND attribute_not_exists(paymentAttemptId) AND "
                + "attribute_not_exists(quarantinedAt) AND expiresAtMs > " + cutoff.toEpochMilli());
        assertThat(lines.get(1)).isEqualTo("Update TICKET#" + EVENT_ID + "#A-1-1 #META | SET state = 'PENDING_CONFIRMATION', "
                + "updatedAt = '" + NOW.plusSeconds(5) + "' | IF state = 'RESERVED' AND orderId = '" + ORDER_ID + "'");
        assertThat(lines.getLast()).startsWith("Put ORDER#" + ORDER_ID + " AUDIT#");
    }

    @Test
    @DisplayName("AP-014 AC-012 addendum confirming 10 Tickets is one transaction of 13 items: Order, Tickets SOLD, audit and lock removal")
    void confirmItems() {
        Order started = order.startPayment(NOW.plusSeconds(5));
        Order confirmed = started.recordPaymentOutcome(PaymentOutcome.APPROVED).confirm(NOW.plusSeconds(8));
        store.confirm(new ConfirmationPlan(started, confirmed, "provider-ref",
                audit(AuditCode.PAYMENT_APPROVED, confirmed, NOW.plusSeconds(8)), NOW.plusSeconds(8))).block();

        List<String> lines = render(lastTransaction());
        assertThat(lines).hasSize(13);
        assertThat(lines.getFirst()).isEqualTo("Update ORDER#" + ORDER_ID + " #META | SET status = 'CONFIRMED', updatedAt = '"
                + NOW.plusSeconds(8) + "', terminalAt = '" + NOW.plusSeconds(8) + "', paymentProviderRef = 'provider-ref', "
                + "paymentOutcome = 'APPROVED', paymentCompletedAt = '" + NOW.plusSeconds(8) + "' REMOVE GSI3PK, GSI3SK, "
                + "GSI4PK, GSI4SK, paymentLeaseOwner, paymentLeaseUntilMs | IF status = 'CREATED' AND "
                + "attribute_not_exists(quarantinedAt) AND paymentAttemptId = '" + ORDER_ID + "-1' AND expiresAtMs > "
                + NOW.plusSeconds(8).toEpochMilli());
        assertThat(lines.get(1)).isEqualTo("Update TICKET#" + EVENT_ID + "#A-1-1 #META | SET state = 'SOLD', updatedAt = '"
                + NOW.plusSeconds(8) + "' | IF state = 'PENDING_CONFIRMATION' AND orderId = '" + ORDER_ID + "'");
        assertThat(lines.getLast()).isEqualTo("Delete ACTIVE#customer-1#" + EVENT_ID
                + " #META | IF (attribute_not_exists(PK) OR orderId = '" + ORDER_ID + "')");
    }

    @Test
    @DisplayName("AP-015 BR-012 addendum expiring releases each Ticket into its GSI2 shard in one transaction of 13 items")
    void expireItems() {
        Instant expiry = order.reservation().expiresAt();
        Order expired = order.expire(expiry);
        store.close(new ClosurePlan(ClosurePlan.Kind.EXPIRE, order, expired, null,
                audit(AuditCode.RESERVATION_EXPIRED, expired, expiry), expiry)).block();

        List<String> lines = render(lastTransaction());
        assertThat(lines).hasSize(13);
        assertThat(lines.getFirst()).isEqualTo("Update ORDER#" + ORDER_ID + " #META | SET status = 'EXPIRED', updatedAt = '"
                + expiry + "', terminalAt = '" + expiry + "', failureCause = 'RESERVATION_EXPIRED' REMOVE GSI3PK, GSI3SK, "
                + "GSI4PK, GSI4SK, paymentLeaseOwner, paymentLeaseUntilMs | IF status = 'CREATED' AND "
                + "attribute_not_exists(quarantinedAt) AND expiresAtMs <= " + expiry.toEpochMilli());
        int shard = ShardingPolicy.shard("A-1-1", EVENT.availabilityShards());
        assertThat(lines.get(1)).isEqualTo("Update TICKET#" + EVENT_ID + "#A-1-1 #META | SET state = 'AVAILABLE', updatedAt = '"
                + expiry + "', GSI2PK = 'AVAIL#" + EVENT_ID + "#" + shard + "', GSI2SK = 'A#1#0001' REMOVE orderId | IF "
                + "state IN ('RESERVED', 'PENDING_CONFIRMATION') AND orderId = '" + ORDER_ID + "'");
    }

    @Test
    @DisplayName("AP-015 FG-003 rejecting needs the same PaymentAttempt; a processing failure with unknown outcome marks the reversal in REVERSAL#")
    void rejectAndFailItems() {
        Order started = order.startPayment(NOW.plusSeconds(5));
        Order rejected = started.recordPaymentOutcome(PaymentOutcome.DECLINED).reject();
        store.close(new ClosurePlan(ClosurePlan.Kind.REJECT, started, rejected, "ref-1",
                audit(AuditCode.PAYMENT_DECLINED, rejected, NOW.plusSeconds(7)), NOW.plusSeconds(7))).block();
        assertThat(render(lastTransaction()).getFirst()).contains("paymentOutcome = 'DECLINED', paymentCompletedAt = '")
                .contains("paymentProviderRef = 'ref-1'")
                .endsWith("IF status = 'CREATED' AND attribute_not_exists(quarantinedAt) AND paymentAttemptId = '"
                        + ORDER_ID + "-1'");

        Order failed = started.failProcessing(NOW.plusSeconds(9));
        store.close(new ClosurePlan(ClosurePlan.Kind.FAIL_PROCESSING, started, failed, null,
                audit(AuditCode.PROCESSING_FAILED, failed, NOW.plusSeconds(9)), NOW.plusSeconds(9))).block();
        String failure = render(lastTransaction()).getFirst();
        long nine = NOW.plusSeconds(9).toEpochMilli();
        assertThat(failure).contains("failureCause = 'PROCESSING_FAILED'", "paymentOutcome = 'UNKNOWN'",
                "paymentReversalPending = true", "paymentReversalRequestedAt = '" + NOW.plusSeconds(9) + "'",
                "paymentReversalAttempts = 0", "paymentReversalNextAttemptAtMs = " + nine,
                "GSI3PK = 'REVERSAL#" + ShardingPolicy.shard(ORDER_ID, 4) + "'", "GSI3SK = '" + Keys.millis13(nine) + "#" + ORDER_ID + "'")
                .contains("REMOVE GSI4PK, GSI4SK, paymentLeaseOwner, paymentLeaseUntilMs")
                .doesNotContain("paymentCompletedAt")
                .endsWith("AND paymentAttemptId = '" + ORDER_ID + "-1'");

        Order withoutAttempt = order.failProcessing(NOW.plusSeconds(9));
        store.close(new ClosurePlan(ClosurePlan.Kind.FAIL_PROCESSING, order, withoutAttempt, null,
                audit(AuditCode.PROCESSING_FAILED, withoutAttempt, NOW.plusSeconds(10)), NOW.plusSeconds(10))).block();
        assertThat(render(lastTransaction()).getFirst()).endsWith("AND attribute_not_exists(paymentAttemptId)")
                .doesNotContain("paymentReversalPending");
    }

    @Test
    @DisplayName("AP-015 ST-009 Fail (enqueue) is 13 items guarded by CREATED without PaymentAttempt")
    void failEnqueueItems() {
        Order failed = order.failEnqueue();
        store.failEnqueue(new EnqueueFailurePlan(order, failed, audit(AuditCode.ENQUEUE_FAILED, failed, NOW.plusSeconds(3)),
                NOW.plusSeconds(3))).block();

        List<String> lines = render(lastTransaction());
        assertThat(lines).hasSize(13);
        assertThat(lines.getFirst()).contains("SET status = 'FAILED'", "failureCause = 'PROCESSING_UNAVAILABLE'")
                .endsWith("IF status = 'CREATED' AND attribute_not_exists(paymentAttemptId)");
    }

    @Test
    @DisplayName("AP-031 ADR-025 quarantine writes 2 items: Order into REVIEW#QUARANTINE out of GSI4, and the audit; no Ticket nor lock")
    void quarantineItems() {
        Order quarantined = order.quarantine(NOW.plusSeconds(4), "TICKET_CONDITION_FAILED_ON_EXPIRATION");
        store.quarantine(new QuarantinePlan(order, quarantined, audit(AuditCode.ORDER_QUARANTINED, order, NOW.plusSeconds(4))))
                .block();

        List<String> lines = render(lastTransaction());
        assertThat(lines).hasSize(2);
        assertThat(lines.getFirst()).isEqualTo("Update ORDER#" + ORDER_ID + " #META | SET quarantinedAt = '" + NOW.plusSeconds(4)
                + "', quarantineReason = 'TICKET_CONDITION_FAILED_ON_EXPIRATION', updatedAt = '" + NOW.plusSeconds(4)
                + "', GSI3PK = 'REVIEW#QUARANTINE', GSI3SK = '" + Keys.sortable(NOW.plusSeconds(4)) + "#" + ORDER_ID
                + "' REMOVE GSI4PK, GSI4SK | IF status = 'CREATED' AND attribute_not_exists(quarantinedAt)");
    }

    @Test
    @DisplayName("AP-032 ADR-008 a late approval marks the reversal when none was read, otherwise writes only the audit with a condition check")
    void lateApprovalItems() {
        Order started = order.startPayment(NOW.plusSeconds(5));
        Order rejected = started.recordPaymentOutcome(PaymentOutcome.DECLINED).reject();
        Order marked = rejected.recordLateApproval(NOW.plusSeconds(20));
        store.recordLateApproval(new LateApprovalPlan(rejected, marked,
                audit(AuditCode.LATE_APPROVAL_NOT_APPLIED, rejected, NOW.plusSeconds(20)))).block();
        List<String> mark = render(lastTransaction());
        assertThat(mark).hasSize(2);
        assertThat(mark.getFirst()).contains("paymentReversalPending = true", "GSI3PK = 'REVERSAL#")
                .endsWith("IF status IN ('EXPIRED', 'FAILED', 'REJECTED') AND paymentAttemptId = '" + ORDER_ID
                        + "-1' AND attribute_not_exists(paymentReversalRequestedAt)");

        store.recordLateApproval(new LateApprovalPlan(marked, marked,
                audit(AuditCode.LATE_APPROVAL_NOT_APPLIED, marked, NOW.plusSeconds(30)))).block();
        assertThat(render(lastTransaction()).getFirst()).isEqualTo("Check ORDER#" + ORDER_ID
                + " #META | IF status IN ('EXPIRED', 'FAILED', 'REJECTED') AND paymentAttemptId = '" + ORDER_ID + "-1'");
    }

    @Test
    @DisplayName("AP-030 FG-003 completing and exhausting a reversal are 2-item transactions; rescheduling is a conditional update")
    void reversalItems() {
        Order failed = order.startPayment(NOW.plusSeconds(5)).failProcessing(NOW.plusSeconds(9));
        Order completed = failed.completeReversal(NOW.plusSeconds(25));
        store.completeReversal(new ReversalCompletionPlan(failed, completed,
                audit(AuditCode.PAYMENT_REVERSAL_CONFIRMED, completed, NOW.plusSeconds(25)))).block();
        assertThat(render(lastTransaction())).hasSize(2).first().asString().isEqualTo("Update ORDER#" + ORDER_ID
                + " #META | SET paymentReversalCompletedAt = '" + NOW.plusSeconds(25) + "', updatedAt = '" + NOW.plusSeconds(25)
                + "' REMOVE paymentReversalPending, GSI3PK, GSI3SK | IF paymentReversalPending = true AND paymentAttemptId = '"
                + ORDER_ID + "-1'");

        Order exhausted = failed;
        for (int attempt = 0; attempt < 10; attempt++) {
            exhausted = exhausted.rescheduleReversal(NOW.plusSeconds(60));
        }
        store.exhaustReversal(new ReversalExhaustionPlan(failed, exhausted,
                audit(AuditCode.PAYMENT_REVERSAL_EXHAUSTED, exhausted, NOW.plusSeconds(60)))).block();
        assertThat(render(lastTransaction())).hasSize(2).first().asString()
                .contains("paymentReversalAttempts = 10", "paymentReversalExhaustedAt = '" + NOW.plusSeconds(60) + "'",
                        "GSI3PK = 'REVERSAL#EXHAUSTED'", "GSI3SK = '" + Keys.sortable(NOW.plusSeconds(60)) + "#" + ORDER_ID + "'")
                .endsWith("IF paymentReversalPending = true");

        when(client.updateItem(any(UpdateItemRequest.class))).thenReturn(done(UpdateItemResponse.builder().build()));
        Order rescheduled = failed.rescheduleReversal(NOW.plusSeconds(10));
        assertThat(store.rescheduleReversal(ORDER_ID, 0, rescheduled.reversalPlan()).block()).isTrue();
        long next = rescheduled.reversalPlan().nextAttemptAt().toEpochMilli();
        assertThat(render(lastUpdate())).isEqualTo("Update ORDER#" + ORDER_ID + " #META | SET paymentReversalAttempts = 1, "
                + "paymentReversalNextAttemptAtMs = " + next + ", GSI3SK = '" + Keys.millis13(next) + "#" + ORDER_ID
                + "' | IF paymentReversalPending = true AND paymentReversalAttempts = 0");
    }

    @Test
    @DisplayName("AP-011 AP-013 single conditional writes return true when applied, false on a failed condition and fail on other errors")
    void singleConditionalWrites() {
        when(client.updateItem(any(UpdateItemRequest.class)))
                .thenReturn(done(UpdateItemResponse.builder().build()))
                .thenReturn(failed(ConditionalCheckFailedException.builder().message("no").build()))
                .thenReturn(failed(ProvisionedThroughputExceededException.builder().message("slow").build()));

        assertThat(store.markEnqueued(ORDER_ID, NOW.plusSeconds(1)).block()).isTrue();
        assertThat(render(lastUpdate())).isEqualTo("Update ORDER#" + ORDER_ID + " #META | SET enqueuedAt = '"
                + NOW.plusSeconds(1) + "' REMOVE GSI4PK, GSI4SK | IF status = 'CREATED' AND attribute_not_exists(enqueuedAt)");
        assertThat(store.claimPaymentLease(ORDER_ID, ORDER_ID + "-1", new PaymentLease("w/2", NOW.plusSeconds(99)),
                NOW.plusSeconds(51)).block()).isFalse();
        assertThat(render(lastUpdate())).isEqualTo("Update ORDER#" + ORDER_ID + " #META | SET paymentLeaseOwner = 'w/2', "
                + "paymentLeaseUntilMs = " + NOW.plusSeconds(99).toEpochMilli() + ", updatedAt = '" + NOW.plusSeconds(51)
                + "' | IF status = 'CREATED' AND paymentAttemptId = '" + ORDER_ID + "-1' AND paymentLeaseUntilMs < "
                + NOW.plusSeconds(51).toEpochMilli() + " AND attribute_not_exists(quarantinedAt)");
        StepVerifier.create(store.markEnqueued(ORDER_ID, NOW)).expectError(DynamoDbStoreException.class).verify();
    }

    @Test
    @DisplayName("ADR-039 AC-047 cancellation reasons are mapped per item: missing or other-Event Ticket, unavailable Ticket, Order, idempotency, audit, lock")
    void cancellationReasons() {
        Map<String, AttributeValue> available = Map.of("eventId", AttributeValue.fromS(EVENT_ID),
                "state", AttributeValue.fromS("SOLD"));
        Map<String, AttributeValue> otherEvent = Map.of("eventId", AttributeValue.fromS("other"));
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class))).thenReturn(failed(cancelled(
                conditionFailed(available), conditionFailed(null), conditionFailed(otherEvent), none(), none(), none(),
                none(), none(), none(), none(),
                conditionFailed(null), conditionFailed(null), conditionFailed(null), conditionFailed(null))));

        TransactionOutcome.Cancelled outcome = (TransactionOutcome.Cancelled) store.reserve(plan(order)).block();
        assertThat(outcome.failures()).containsExactly(
                ItemFailure.ticket(FailedItem.TICKET_STATE, "A-1-1"),
                ItemFailure.ticket(FailedItem.TICKET_MISSING, "A-1-2"),
                ItemFailure.ticket(FailedItem.TICKET_MISSING, "A-1-3"),
                ItemFailure.of(FailedItem.ORDER),
                ItemFailure.of(FailedItem.IDEMPOTENCY_RECORD),
                ItemFailure.of(FailedItem.AUDIT),
                ItemFailure.of(FailedItem.ACTIVE_ORDER_LOCK));
        verify(client, times(1)).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    @DisplayName("ADR-023 ADR-035 a transactional conflict retries the whole transaction at most twice, then reports a persistent conflict")
    void conflictRetry() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(failed(cancelled(code("TransactionConflict"), none())))
                .thenReturn(failed(cancelled(none(), code("TransactionConflict"))))
                .thenReturn(done(TransactWriteItemsResponse.builder().build()));
        Order quarantined = order.quarantine(NOW, "reason");
        QuarantinePlan plan = new QuarantinePlan(order, quarantined, audit(AuditCode.ORDER_QUARANTINED, order, NOW));
        assertThat(store.quarantine(plan).block()).isEqualTo(TransactionOutcome.applied());
        verify(client, times(3)).transactWriteItems(any(TransactWriteItemsRequest.class));

        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(failed(cancelled(code("TransactionConflict"), none())));
        assertThat(store.quarantine(plan).block()).isEqualTo(TransactionOutcome.conflict());
        verify(client, times(6)).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    @DisplayName("ADR-035 throttling, unknown or missing cancellation reasons and SDK failures are technical errors, never a typed outcome")
    void technicalErrors() {
        Order quarantined = order.quarantine(NOW, "reason");
        QuarantinePlan plan = new QuarantinePlan(order, quarantined, audit(AuditCode.ORDER_QUARANTINED, order, NOW));
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(failed(cancelled(code("ThrottlingError"), none())))
                .thenReturn(failed(cancelled(none(), none())))
                .thenReturn(failed(ProvisionedThroughputExceededException.builder().message("slow").build()));

        StepVerifier.create(store.quarantine(plan)).expectError(DynamoDbStoreException.class).verify();
        StepVerifier.create(store.quarantine(plan)).expectError(DynamoDbStoreException.class).verify();
        StepVerifier.create(store.quarantine(plan)).expectErrorSatisfies(error -> assertThat(error)
                .isInstanceOf(DynamoDbStoreException.class)
                .hasCauseInstanceOf(ProvisionedThroughputExceededException.class)).verify();
    }

    @Test
    @DisplayName("ADR-022 releasing Tickets needs the Event shards; an Order whose Event is missing is a technical error")
    void releaseNeedsTheEvent() {
        when(client.getItem(any(GetItemRequest.class))).thenReturn(done(GetItemResponse.builder().build()));
        Order failed = order.failEnqueue();

        StepVerifier.create(store.failEnqueue(new EnqueueFailurePlan(order, failed,
                        audit(AuditCode.ENQUEUE_FAILED, failed, NOW), NOW)))
                .expectError(DynamoDbStoreException.class)
                .verify();
        verify(client, never()).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    // ------------------------------------------------------------------ helpers

    private static ReservationPlan plan(Order reservation) {
        return PersistencePortContract.reservationPlan(reservation);
    }

    private TransactWriteItemsRequest lastTransaction() {
        ArgumentCaptor<TransactWriteItemsRequest> captor = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(client, org.mockito.Mockito.atLeastOnce()).transactWriteItems(captor.capture());
        return captor.getValue();
    }

    private UpdateItemRequest lastUpdate() {
        ArgumentCaptor<UpdateItemRequest> captor = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(client, org.mockito.Mockito.atLeastOnce()).updateItem(captor.capture());
        return captor.getValue();
    }

    private static ReturnValuesOnConditionCheckFailure returnValues(TransactWriteItem item) {
        if (item.update() != null) {
            return item.update().returnValuesOnConditionCheckFailure();
        }
        return item.put().returnValuesOnConditionCheckFailure();
    }
}
