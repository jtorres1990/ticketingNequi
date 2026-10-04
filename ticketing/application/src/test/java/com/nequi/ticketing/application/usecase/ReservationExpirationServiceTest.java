package com.nequi.ticketing.application.usecase;

import static com.nequi.ticketing.application.usecase.Rejections.value;
import static com.nequi.ticketing.application.usecase.WorkerFixture.CUSTOMER_A;
import static com.nequi.ticketing.application.usecase.WorkerFixture.CUSTOMER_B;
import static com.nequi.ticketing.application.usecase.WorkerFixture.EVENT_ID;
import static com.nequi.ticketing.application.usecase.WorkerFixture.NOW;
import static com.nequi.ticketing.application.usecase.WorkerFixture.key;
import static org.assertj.core.api.Assertions.assertThat;

import com.nequi.ticketing.application.port.in.CycleRequest;
import com.nequi.ticketing.application.port.in.CycleResult;
import com.nequi.ticketing.application.port.in.ItemOutcome;
import com.nequi.ticketing.application.port.out.OrderRecord;
import com.nequi.ticketing.application.port.out.PaymentLease;
import com.nequi.ticketing.application.port.out.TransactionOutcome;
import com.nequi.ticketing.application.testdouble.InMemoryTicketingStore.Operation;
import com.nequi.ticketing.domain.audit.AuditRecord;
import com.nequi.ticketing.domain.audit.AuditRecord.AuditCode;
import com.nequi.ticketing.domain.event.ShardingPolicy;
import com.nequi.ticketing.domain.order.Order;
import com.nequi.ticketing.domain.order.Order.FunctionalCause;
import com.nequi.ticketing.domain.order.Order.OrderStatus;
import com.nequi.ticketing.domain.ticket.Ticket.TicketState;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReservationExpirationServiceTest {

    private WorkerFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new WorkerFixture();
        fixture.seedEvent();
    }

    @Test
    @DisplayName("AC-008 FR-011 BR-011 ST-002 ST-010 an expired Reservation is expired and all its Tickets are released together")
    void expiresDueReservations() {
        Order order = fixture.createOrder(CUSTOMER_A, key(1), "A-1-1", "A-1-2", "A-1-3");
        fixture.clock.set(order.reservation().expiresAt());

        CycleResult result = fixture.expire();

        OrderRecord stored = fixture.order(order.orderId());
        assertThat(result.count(ItemOutcome.EXPIRED)).isEqualTo(1);
        assertThat(stored.order().status()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(stored.order().failureCause()).isEqualTo(FunctionalCause.RESERVATION_EXPIRED);
        assertThat(stored.order().reversalPlan()).isNull();
        assertThat(fixture.states(order)).containsOnly(TicketState.AVAILABLE);
        assertThat(fixture.store.activeLock(CUSTOMER_A, EVENT_ID)).isEmpty();
        AuditRecord audit = fixture.store.audits().getLast();
        assertThat(audit.code()).isEqualTo(AuditCode.RESERVATION_EXPIRED);
        assertThat(audit.transitionIds()).containsExactly("ST-002", "ST-010");
        assertThat(audit.correlationId()).isEqualTo(WorkerFixture.CORRELATION);
        assertThat(audit.includedCodes()).isEmpty();
    }

    @Test
    @DisplayName("AC-009 VAL-003 BR-002 a Reservation whose expiresAt has not been reached is never released")
    void neverTouchesAValidReservation() {
        Order order = fixture.createOrder(CUSTOMER_A, key(1), "A-1-1");
        fixture.clock.set(order.reservation().expiresAt().minusMillis(1));

        CycleResult result = fixture.expire();

        assertThat(result.total()).isZero();
        assertThat(fixture.order(order.orderId()).order().status()).isEqualTo(OrderStatus.CREATED);
        assertThat(fixture.states(order)).containsOnly(TicketState.RESERVED);
    }

    @Test
    @DisplayName("AC-009 ADR-028 a stale index entry for a Reservation that is still valid is ignored after the strong read")
    void staleIndexEntryIsIgnored() {
        Order order = fixture.createOrder(CUSTOMER_A, key(1), "A-1-1");
        fixture.clock.set(order.reservation().expiresAt());
        fixture.store.beforeNext(Operation.FIND_ORDER, () -> fixture.clock.set(order.reservation().expiresAt().minusSeconds(1)));

        CycleResult result = fixture.expire();

        assertThat(result.count(ItemOutcome.NOT_APPLICABLE)).isEqualTo(1);
        assertThat(fixture.order(order.orderId()).order().status()).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    @DisplayName("AC-008 FG-003 BR-028 expiring an Order with a PaymentAttempt marks the payment reversal in the same transition")
    void expiringWithPaymentAttemptMarksReversal() {
        Order order = fixture.createOrder(CUSTOMER_A, key(1), "A-1-1");
        Order started = order.startPayment(NOW);
        fixture.store.putOrder(new OrderRecord(started, NOW, NOW, NOW, new PaymentLease("worker-2/1", NOW.plusSeconds(45))));
        fixture.store.setTicket(EVENT_ID, "A-1-1", TicketState.PENDING_CONFIRMATION, order.orderId());
        fixture.clock.set(order.reservation().expiresAt().plusSeconds(3));

        fixture.expire();

        OrderRecord stored = fixture.order(order.orderId());
        assertThat(stored.order().status()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(stored.order().reversalPending()).isTrue();
        assertThat(stored.paymentLease()).isNull();
        assertThat(fixture.ticket("A-1-1").state()).isEqualTo(TicketState.AVAILABLE);
        AuditRecord audit = fixture.store.audits().getLast();
        assertThat(audit.includedCodes()).containsExactly(AuditCode.PAYMENT_REVERSAL_REQUESTED);
        assertThat(audit.ticketFrom()).isEqualTo(TicketState.PENDING_CONFIRMATION);
    }

    @Test
    @DisplayName("ADR-038/mechanism ADR-025 an expiration cancelled by a Ticket condition quarantines the Order without touching the Tickets")
    void ticketConditionQuarantines() {
        Order order = fixture.createOrder(CUSTOMER_A, key(1), "A-1-1", "A-1-2");
        fixture.store.setTicket(EVENT_ID, "A-1-2", TicketState.SOLD, "other-order");
        fixture.clock.set(order.reservation().expiresAt());

        CycleResult first = fixture.expire();
        CycleResult second = fixture.expire();

        OrderRecord stored = fixture.order(order.orderId());
        assertThat(first.count(ItemOutcome.QUARANTINED)).isEqualTo(1);
        assertThat(second.total()).isZero();
        assertThat(stored.order().status()).isEqualTo(OrderStatus.CREATED);
        assertThat(stored.order().quarantineReason()).isEqualTo(OrderQuarantine.ON_EXPIRATION);
        assertThat(fixture.ticket("A-1-1").state()).isEqualTo(TicketState.RESERVED);
        assertThat(fixture.ticket("A-1-2").state()).isEqualTo(TicketState.SOLD);
        assertThat(fixture.store.activeLock(CUSTOMER_A, EVENT_ID)).contains(order.orderId());
        assertThat(fixture.store.audits().getLast().code()).isEqualTo(AuditCode.ORDER_QUARANTINED);
        assertThat(OrderViews.of(stored).status()).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    @DisplayName("ADR-028 an expiration lost to a concurrent terminal transition is not applicable and changes nothing")
    void concurrentTerminalTransitionWins() {
        Order order = fixture.createOrder(CUSTOMER_A, key(1), "A-1-1");
        fixture.clock.set(order.reservation().expiresAt());
        fixture.store.beforeNext(Operation.CLOSE, () -> fixture.store.putOrder(new OrderRecord(
                order.failProcessing(NOW), NOW, NOW, NOW)));

        CycleResult result = fixture.expire();

        assertThat(result.count(ItemOutcome.NOT_APPLICABLE)).isEqualTo(1);
        assertThat(fixture.order(order.orderId()).order().status()).isEqualTo(OrderStatus.FAILED);
    }

    @Test
    @DisplayName("ADR-028 rule 3 a failing candidate or index query never stops the rest of the cycle")
    void failuresAreIsolated() {
        Order first = fixture.createOrder(CUSTOMER_A, key(1), "A-1-1");
        Order second = fixture.createOrder(CUSTOMER_B, key(2), "A-1-2");
        fixture.clock.set(first.reservation().expiresAt().plusSeconds(1));
        fixture.store.failNext(Operation.FIND_ORDER, 1);

        CycleResult withCandidateFailure = fixture.expire();

        assertThat(withCandidateFailure.count(ItemOutcome.FAILED)).isEqualTo(1);
        assertThat(withCandidateFailure.count(ItemOutcome.EXPIRED)).isEqualTo(1);

        fixture.store.failNext(Operation.FIND_DUE_RESERVATIONS, 1);
        CycleResult withQueryFailure = fixture.expire();

        assertThat(withQueryFailure.count(ItemOutcome.FAILED)).isEqualTo(1);
        assertThat(List.of(first, second)).allSatisfy(order ->
                assertThat(fixture.order(order.orderId()).order().status()).isEqualTo(OrderStatus.EXPIRED));
    }

    @Test
    @DisplayName("ADR-035 a persistent conflict and a failed quarantine are counted and retried by the next cycle")
    void conflictsAreRetriedNextCycle() {
        Order order = fixture.createOrder(CUSTOMER_A, key(1), "A-1-1");
        fixture.clock.set(order.reservation().expiresAt());
        fixture.store.script(Operation.CLOSE, TransactionOutcome.conflict());

        assertThat(fixture.expire().count(ItemOutcome.FAILED)).isEqualTo(1);

        fixture.store.setTicket(EVENT_ID, "A-1-1", TicketState.AVAILABLE, null);
        fixture.store.script(Operation.QUARANTINE, TransactionOutcome.conflict());
        assertThat(fixture.expire().count(ItemOutcome.FAILED)).isEqualTo(1);

        fixture.store.script(Operation.QUARANTINE, TransactionOutcome.cancelled(List.of(
                com.nequi.ticketing.application.port.out.ItemFailure.of(
                        com.nequi.ticketing.application.port.out.FailedItem.ORDER))));
        assertThat(fixture.expire().count(ItemOutcome.NOT_APPLICABLE)).isEqualTo(1);
        assertThat(fixture.order(order.orderId()).order().status()).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    @DisplayName("ADR-028 the scheduler controls the shard visiting order; unknown shards are ignored")
    void visitsOnlyRequestedShards() {
        Order order = fixture.createOrder(CUSTOMER_A, key(1), "A-1-1");
        fixture.clock.set(order.reservation().expiresAt());
        int shard = ShardingPolicy.shard(order.orderId(), ShardingPolicy.RESERVATION_SHARDS);
        int other = (shard + 1) % ShardingPolicy.RESERVATION_SHARDS;

        CycleResult skipped = value(fixture.expiration.expireDue(new CycleRequest(WorkerFixture.CORRELATION,
                List.of(other, 99, -1))));
        CycleResult visited = value(fixture.expiration.expireDue(new CycleRequest(WorkerFixture.CORRELATION,
                List.of(other, shard, shard))));

        assertThat(skipped.total()).isZero();
        assertThat(visited.count(ItemOutcome.EXPIRED)).isEqualTo(1);
    }

    @Test
    @DisplayName("ALT-013 AC-045 after the expiration the customer can start a new purchase for the same Event")
    void expirationReleasesTheActiveOrderLock() {
        Order order = fixture.createOrder(CUSTOMER_A, key(1), "A-1-1");
        fixture.clock.set(order.reservation().expiresAt().plus(Duration.ofSeconds(2)));
        fixture.expire();

        Order next = fixture.createOrder(CUSTOMER_A, key(2), "A-1-1");

        assertThat(next.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(fixture.store.activeLock(CUSTOMER_A, EVENT_ID)).contains(next.orderId());
    }
}
