package com.nequi.ticketing.application.usecase;

import static com.nequi.ticketing.application.usecase.WorkerFixture.CUSTOMER_A;
import static com.nequi.ticketing.application.usecase.WorkerFixture.CUSTOMER_B;
import static com.nequi.ticketing.application.usecase.WorkerFixture.EVENT_ID;
import static com.nequi.ticketing.application.usecase.WorkerFixture.NOW;
import static com.nequi.ticketing.application.usecase.WorkerFixture.key;
import static com.nequi.ticketing.application.usecase.Rejections.value;
import static org.assertj.core.api.Assertions.assertThat;

import com.nequi.ticketing.application.port.in.Delivery;
import com.nequi.ticketing.application.port.in.DispositionReason;
import com.nequi.ticketing.application.port.in.MessageDisposition;
import com.nequi.ticketing.application.port.in.ProcessOrderCommand;
import com.nequi.ticketing.application.port.out.AuthorizationOutcome;
import com.nequi.ticketing.application.port.out.OrderRecord;
import com.nequi.ticketing.application.port.out.PaymentLease;
import com.nequi.ticketing.application.testdouble.InMemoryTicketingStore.Operation;
import com.nequi.ticketing.domain.audit.AuditRecord;
import com.nequi.ticketing.domain.audit.AuditRecord.ActorType;
import com.nequi.ticketing.domain.audit.AuditRecord.AuditCode;
import com.nequi.ticketing.domain.event.ShardingPolicy;
import com.nequi.ticketing.domain.order.Order;
import com.nequi.ticketing.domain.order.Order.FunctionalCause;
import com.nequi.ticketing.domain.order.Order.OrderStatus;
import com.nequi.ticketing.domain.ticket.Ticket.TicketState;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class OrderProcessingServiceTest {

    private WorkerFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new WorkerFixture();
        fixture.seedEvent();
    }

    // ------------------------------------------------------------------ happy path

    @Test
    @DisplayName("AC-005 ST-003 FR-015 starting the payment moves every Ticket to PENDING_CONFIRMATION before the provider is called")
    void startsPaymentBeforeAuthorizing() {
        Order order = fixture.createOrder(CUSTOMER_A, key(1), "A-1-1", "A-1-2");
        AtomicReference<List<TicketState>> statesAtAuthorization = new AtomicReference<>();
        AtomicReference<OrderStatus> statusAtAuthorization = new AtomicReference<>();
        fixture.gateway.onAuthorize(() -> {
            statesAtAuthorization.set(fixture.states(order));
            statusAtAuthorization.set(fixture.order(order.orderId()).order().status());
        });

        fixture.process(order.orderId());

        assertThat(statesAtAuthorization.get()).containsOnly(TicketState.PENDING_CONFIRMATION);
        assertThat(statusAtAuthorization.get()).isEqualTo(OrderStatus.CREATED);
        AuditRecord started = audit(order.orderId(), AuditCode.PAYMENT_STARTED);
        assertThat(started.transitionIds()).containsExactly("ST-003");
        assertThat(started.ticketFrom()).isEqualTo(TicketState.RESERVED);
        assertThat(started.ticketTo()).isEqualTo(TicketState.PENDING_CONFIRMATION);
        assertThat(started.paymentAttemptId()).isEqualTo(order.orderId() + "-1");
        assertThat(started.actor().type()).isEqualTo(ActorType.WORKER);
        assertThat(started.actor().id()).isEqualTo(WorkerFixture.WORKER);
    }

    @Test
    @DisplayName("AC-019 ST-004 ST-007 BR-003 an approval applied before expiresAt confirms the Order and sells every Ticket once")
    void approvalConfirmsTheOrder() {
        Order order = fixture.createOrder(CUSTOMER_A, key(1), "A-1-1", "A-1-2");

        MessageDisposition disposition = fixture.process(order.orderId());

        OrderRecord stored = fixture.order(order.orderId());
        assertThat(disposition).isEqualTo(MessageDisposition.delete(DispositionReason.CONFIRMED));
        assertThat(stored.order().status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(stored.order().failureCause()).isNull();
        assertThat(stored.paymentLease()).isNull();
        assertThat(fixture.states(order)).containsOnly(TicketState.SOLD);
        assertThat(fixture.store.activeLock(CUSTOMER_A, EVENT_ID)).isEmpty();
        AuditRecord approved = audit(order.orderId(), AuditCode.PAYMENT_APPROVED);
        assertThat(approved.transitionIds()).containsExactly("ST-004", "ST-007");
        assertThat(approved.orderTo()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(approved.ticketTo()).isEqualTo(TicketState.SOLD);

        MessageDisposition again = fixture.process(order.orderId());

        assertThat(again).isEqualTo(MessageDisposition.delete(DispositionReason.ALREADY_TERMINAL));
        assertThat(fixture.gateway.authorizations()).hasSize(1);
        assertThat(fixture.store.audits()).filteredOn(record -> record.code() == AuditCode.PAYMENT_APPROVED).hasSize(1);
    }

    @Test
    @DisplayName("ADR-008 ADR-030 the authorization carries the PaymentAttempt and a deadline of expiresAt minus the 2 s margin")
    void authorizationRequestAndDeadline() {
        Order order = fixture.createOrder(CUSTOMER_A, key(1), "A-1-1", "A-1-2");

        fixture.process(order.orderId());

        var call = fixture.gateway.authorizations().getFirst();
        assertThat(call.deadline()).isEqualTo(order.reservation().expiresAt().minusSeconds(2));
        assertThat(call.request().paymentAttemptId()).isEqualTo(order.orderId() + "-1");
        assertThat(call.request().orderId()).isEqualTo(order.orderId());
        assertThat(call.request().eventId()).isEqualTo(EVENT_ID);
        assertThat(call.request().customerRef()).isEqualTo(CUSTOMER_A);
        assertThat(call.request().ticketIds()).containsExactly("A-1-1", "A-1-2");
    }

    @Test
    @DisplayName("AC-051 ALT-012 BR-025 a Reservation created before startsAt is confirmed after the Event started and before expiresAt")
    void confirmsAfterTheEventStarted() {
        fixture.store.seedEnabledEvent("e-soon", "Soon", NOW.plus(Duration.ofMinutes(5)), ApiFixture.DEFINITION);
        String orderId = value(fixture.purchases.startPurchase(new com.nequi.ticketing.application.port.in.StartPurchaseCommand(
                CUSTOMER_A, "e-soon", List.of("A-1-1"), key(1), WorkerFixture.CORRELATION))).order().orderId();
        fixture.clock.advance(Duration.ofMinutes(6));

        MessageDisposition disposition = fixture.process(orderId);

        assertThat(disposition).isEqualTo(MessageDisposition.delete(DispositionReason.CONFIRMED));
        assertThat(fixture.order(orderId).order().status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(fixture.store.ticket("e-soon", "A-1-1").state()).isEqualTo(TicketState.SOLD);
    }

    // ------------------------------------------------------------------ provider outcomes

    @Test
    @DisplayName("AC-020 ALT-005 ST-005 ST-008 a declined payment rejects the Order and releases every Ticket together")
    void declinedPaymentRejects() {
        Order order = fixture.createOrder(CUSTOMER_A, key(1), "A-1-1", "A-1-2", "A-1-3");
        fixture.gateway.scriptAuthorizations(new AuthorizationOutcome.Declined("ref-1", "INSUFFICIENT_FUNDS"));

        MessageDisposition disposition = fixture.process(order.orderId());

        OrderRecord stored = fixture.order(order.orderId());
        assertThat(disposition).isEqualTo(MessageDisposition.delete(DispositionReason.REJECTED));
        assertThat(stored.order().status()).isEqualTo(OrderStatus.REJECTED);
        assertThat(stored.order().failureCause()).isEqualTo(FunctionalCause.PAYMENT_DECLINED);
        assertThat(stored.order().reversalPlan()).isNull();
        assertThat(fixture.states(order)).containsOnly(TicketState.AVAILABLE);
        assertThat(order.ticketIds()).allSatisfy(id -> assertThat(fixture.ticket(id).orderId()).isNull());
        assertThat(fixture.store.activeLock(CUSTOMER_A, EVENT_ID)).isEmpty();
        AuditRecord declined = audit(order.orderId(), AuditCode.PAYMENT_DECLINED);
        assertThat(declined.transitionIds()).containsExactly("ST-005", "ST-008");
        assertThat(declined.cause()).isEqualTo("PAYMENT_DECLINED");
        assertThat(declined.includedCodes()).isEmpty();
    }

    @Test
    @DisplayName("AC-035 BR-034 ALT-009 a DECLINED with ATTEMPT_CANCELLED after an early cancellation rejects without reversal")
    void attemptCancelledIsADecline() {
        Order order = fixture.createOrder(CUSTOMER_A, key(1), "A-1-1");
        value(fixture.gateway.cancel(order.orderId() + "-1"));

        MessageDisposition disposition = fixture.process(order.orderId());

        assertThat(disposition).isEqualTo(MessageDisposition.delete(DispositionReason.REJECTED));
        assertThat(fixture.order(order.orderId()).order().reversalPlan()).isNull();
    }

    @Test
    @DisplayName("AC-021 ERR-008 a definitive provider error fails the Order without reversal and releases every Ticket")
    void definitiveProviderErrorFailsWithoutReversal() {
        Order order = fixture.createOrder(CUSTOMER_A, key(1), "A-1-1", "A-1-2");
        fixture.gateway.scriptAuthorizations(new AuthorizationOutcome.ContractError(422));

        MessageDisposition disposition = fixture.process(order.orderId());

        OrderRecord stored = fixture.order(order.orderId());
        assertThat(disposition).isEqualTo(MessageDisposition.delete(DispositionReason.FAILED_DEFINITIVE));
        assertThat(stored.order().status()).isEqualTo(OrderStatus.FAILED);
        assertThat(stored.order().failureCause()).isEqualTo(FunctionalCause.PROCESSING_FAILED);
        assertThat(stored.order().reversalPlan()).isNull();
        assertThat(fixture.states(order)).containsOnly(TicketState.AVAILABLE);
        assertThat(audit(order.orderId(), AuditCode.PROCESSING_FAILED).includedCodes()).isEmpty();
    }

    @Test
    @DisplayName("AC-021 FG-003 BR-028 a transient failure on the last reception fails the Order with the reversal mark, not exposed")
    void lastReceptionFailsWithReversal() {
        Order order = fixture.createOrder(CUSTOMER_A, key(1), "A-1-1", "A-1-2");
        fixture.gateway.scriptAuthorizations(new AuthorizationOutcome.DependencyUnavailable("timeout"));

        MessageDisposition disposition = fixture.process(order.orderId(), 5, true);

        OrderRecord stored = fixture.order(order.orderId());
        assertThat(disposition).isEqualTo(MessageDisposition.retry(DispositionReason.EXHAUSTED));
        assertThat(stored.order().status()).isEqualTo(OrderStatus.FAILED);
        assertThat(stored.order().failureCause()).isEqualTo(FunctionalCause.PROCESSING_FAILED);
        assertThat(stored.order().reversalPending()).isTrue();
        assertThat(stored.order().reversalPlan().paymentAttemptId()).isEqualTo(order.orderId() + "-1");
        assertThat(fixture.states(order)).containsOnly(TicketState.AVAILABLE);
        assertThat(fixture.store.activeLock(CUSTOMER_A, EVENT_ID)).isEmpty();
        AuditRecord failed = audit(order.orderId(), AuditCode.PROCESSING_FAILED);
        assertThat(failed.includedCodes()).containsExactly(AuditCode.PAYMENT_REVERSAL_REQUESTED);
        assertThat(failed.ticketFrom()).isEqualTo(TicketState.PENDING_CONFIRMATION);
        assertThat(OrderViews.of(stored).failureCause()).isEqualTo(FunctionalCause.PROCESSING_FAILED);
    }

    @Test
    @DisplayName("ALT-004 ERR-005 AC-024 a transient failure keeps the message and the next delivery reuses the same PaymentAttempt")
    void transientFailureRetriesWithTheSameAttempt() {
        Order order = fixture.createOrder(CUSTOMER_A, key(1), "A-1-1");
        fixture.gateway.scriptAuthorizations(new AuthorizationOutcome.DependencyUnavailable("5xx"));

        MessageDisposition first = fixture.process(order.orderId(), 1, false);
        OrderRecord afterFirst = fixture.order(order.orderId());
        MessageDisposition whileLeased = fixture.process(order.orderId(), 2, false);
        fixture.clock.advance(Duration.ofSeconds(46));
        MessageDisposition afterLease = fixture.process(order.orderId(), 3, false);

        assertThat(first).isEqualTo(MessageDisposition.retry(DispositionReason.TRANSIENT_FAILURE));
        assertThat(afterFirst.order().status()).isEqualTo(OrderStatus.CREATED);
        assertThat(afterFirst.paymentLease().until()).isEqualTo(NOW.plusSeconds(45));
        assertThat(whileLeased).isEqualTo(MessageDisposition.postponeUntil(NOW.plusSeconds(45),
                DispositionReason.LEASE_HELD_ELSEWHERE));
        assertThat(afterLease).isEqualTo(MessageDisposition.delete(DispositionReason.CONFIRMED));
        assertThat(fixture.gateway.attemptIds()).containsExactly(order.orderId() + "-1");
        assertThat(fixture.gateway.authorizations()).hasSize(2);
        assertThat(fixture.store.audits()).filteredOn(record -> record.code() == AuditCode.PAYMENT_STARTED).hasSize(1);
    }

    @Test
    @DisplayName("ADR-029 a transient failure on the last reception with the lease of another consumer is postponed, not failed")
    void lastReceptionWithForeignLeaseIsPostponed() {
        Order order = fixture.createOrder(CUSTOMER_A, key(1), "A-1-1");
        Order started = order.startPayment(NOW);
        fixture.store.putOrder(new OrderRecord(started, NOW, NOW, NOW, new PaymentLease("worker-2/1", NOW.plusSeconds(45))));
        fixture.store.setTicket(EVENT_ID, "A-1-1", TicketState.PENDING_CONFIRMATION, order.orderId());
        fixture.store.failNext(Operation.FIND_ORDER, 1);

        MessageDisposition disposition = fixture.process(order.orderId(), 5, true);

        assertThat(disposition).isEqualTo(MessageDisposition.postponeUntil(NOW.plusSeconds(45),
                DispositionReason.LEASE_HELD_ELSEWHERE));
        assertThat(fixture.order(order.orderId()).order().status()).isEqualTo(OrderStatus.CREATED);
    }

    // ------------------------------------------------------------------ cutoff and expiry (rules 5, 10)

    @Test
    @DisplayName("AC-049 BR-029 ALT-011 inside the 15 s cutoff the payment is not started and the message is deleted")
    void cutoffDoesNotStartThePayment() {
        Order order = fixture.createOrder(CUSTOMER_A, key(1), "A-1-1", "A-1-2");
        fixture.clock.set(order.reservation().expiresAt().minusSeconds(15));

        MessageDisposition disposition = fixture.process(order.orderId());

        assertThat(disposition).isEqualTo(MessageDisposition.delete(DispositionReason.PAYMENT_CUTOFF));
        assertThat(fixture.order(order.orderId()).order().paymentAttempt()).isNull();
        assertThat(fixture.states(order)).containsOnly(TicketState.RESERVED);
        assertThat(fixture.gateway.authorizations()).isEmpty();

        fixture.clock.set(order.reservation().expiresAt());
        assertThat(fixture.expire().count(com.nequi.ticketing.application.port.in.ItemOutcome.EXPIRED)).isEqualTo(1);
        assertThat(fixture.order(order.orderId()).order().status()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(fixture.states(order)).containsOnly(TicketState.AVAILABLE);
    }

    @Test
    @DisplayName("AC-049 ST-010 an Order processed after expiresAt without PaymentAttempt is expired by the consumer")
    void expiredOrderIsExpiredByTheConsumer() {
        Order order = fixture.createOrder(CUSTOMER_A, key(1), "A-1-1");
        fixture.clock.set(order.reservation().expiresAt().plusSeconds(1));

        MessageDisposition disposition = fixture.process(order.orderId());

        OrderRecord stored = fixture.order(order.orderId());
        assertThat(disposition).isEqualTo(MessageDisposition.delete(DispositionReason.EXPIRED));
        assertThat(stored.order().status()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(stored.order().reversalPlan()).isNull();
        assertThat(fixture.states(order)).containsOnly(TicketState.AVAILABLE);
        assertThat(audit(order.orderId(), AuditCode.RESERVATION_EXPIRED).ticketFrom()).isEqualTo(TicketState.RESERVED);
    }

    @Test
    @DisplayName("AC-050 ADR-008 FR-015 an approval arriving after expiresAt never confirms: the Order expires with reversal and late approval")
    void approvalAfterExpiryExpiresWithReversal() {
        Order order = fixture.createOrder(CUSTOMER_A, key(1), "A-1-1", "A-1-2");
        fixture.gateway.onAuthorize(() -> fixture.clock.set(order.reservation().expiresAt()));

        MessageDisposition disposition = fixture.process(order.orderId());

        OrderRecord stored = fixture.order(order.orderId());
        assertThat(disposition).isEqualTo(MessageDisposition.delete(DispositionReason.EXPIRED));
        assertThat(stored.order().status()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(stored.order().failureCause()).isEqualTo(FunctionalCause.RESERVATION_EXPIRED);
        assertThat(stored.order().reversalPending()).isTrue();
        assertThat(fixture.states(order)).containsOnly(TicketState.AVAILABLE);
        assertThat(fixture.store.audits()).noneMatch(record -> record.code() == AuditCode.PAYMENT_APPROVED);
        AuditRecord expired = audit(order.orderId(), AuditCode.RESERVATION_EXPIRED);
        assertThat(expired.includedCodes()).containsExactlyInAnyOrder(
                AuditCode.LATE_APPROVAL_NOT_APPLIED, AuditCode.PAYMENT_REVERSAL_REQUESTED);

        assertThat(fixture.reverse().count(com.nequi.ticketing.application.port.in.ItemOutcome.REVERSAL_CONFIRMED))
                .isEqualTo(1);
        assertThat(fixture.gateway.cancellationCalls()).containsExactly(order.orderId() + "-1");
    }

    @Test
    @DisplayName("AC-034 ERR-016 ADR-008 an approval for an Order already expired keeps it EXPIRED and requests a single cancellation")
    void approvalForAnAlreadyExpiredOrder() {
        Order order = fixture.createOrder(CUSTOMER_A, key(1), "A-1-1", "A-1-2");
        fixture.gateway.onAuthorize(() -> {
            fixture.clock.set(order.reservation().expiresAt().plusSeconds(1));
            fixture.expire();
        });

        MessageDisposition disposition = fixture.process(order.orderId());

        OrderRecord stored = fixture.order(order.orderId());
        assertThat(disposition).isEqualTo(MessageDisposition.delete(DispositionReason.LATE_APPROVAL_RECORDED));
        assertThat(stored.order().status()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(fixture.states(order)).containsOnly(TicketState.AVAILABLE);
        AuditRecord late = audit(order.orderId(), AuditCode.LATE_APPROVAL_NOT_APPLIED);
        assertThat(late.includedCodes()).isEmpty();
        assertThat(audit(order.orderId(), AuditCode.RESERVATION_EXPIRED).includedCodes())
                .containsExactly(AuditCode.PAYMENT_REVERSAL_REQUESTED);

        fixture.reverse();
        fixture.clock.advance(Duration.ofMinutes(30));
        fixture.reverse();

        assertThat(fixture.gateway.cancellationCalls()).containsExactly(order.orderId() + "-1");
        assertThat(fixture.order(order.orderId()).order().status()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(fixture.order(order.orderId()).order().reversalPending()).isFalse();
    }

    @Test
    @DisplayName("AC-025 ADR-008 a late approval on an Order rejected meanwhile marks the reversal without reopening it")
    void lateApprovalOnARejectedOrderMarksTheReversal() {
        Order order = fixture.createOrder(CUSTOMER_A, key(1), "A-1-1");
        Order started = order.startPayment(NOW);
        fixture.store.putOrder(new OrderRecord(started, NOW, NOW, NOW, new PaymentLease("worker-1/1", NOW.plusSeconds(45))));
        fixture.store.setTicket(EVENT_ID, "A-1-1", TicketState.PENDING_CONFIRMATION, order.orderId());
        // Lease expired: this processing reclaims it and authorizes; meanwhile the Order is rejected by another path.
        fixture.clock.advance(Duration.ofSeconds(50));
        fixture.store.beforeNext(Operation.CONFIRM, () -> fixture.store.putOrder(new OrderRecord(
                started.recordPaymentOutcome(Order.PaymentOutcome.DECLINED).reject(), NOW, NOW, NOW)));

        MessageDisposition disposition = fixture.process(order.orderId());

        OrderRecord stored = fixture.order(order.orderId());
        assertThat(disposition).isEqualTo(MessageDisposition.delete(DispositionReason.LATE_APPROVAL_RECORDED));
        assertThat(stored.order().status()).isEqualTo(OrderStatus.REJECTED);
        assertThat(stored.order().reversalPending()).isTrue();
        assertThat(audit(order.orderId(), AuditCode.LATE_APPROVAL_NOT_APPLIED).includedCodes())
                .containsExactly(AuditCode.PAYMENT_REVERSAL_REQUESTED);
    }

    // ------------------------------------------------------------------ idempotency (rules 3, 4, 7, 8)

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = OrderStatus.class, names = {"CONFIRMED", "REJECTED", "FAILED", "EXPIRED"})
    @DisplayName("AC-025 FR-017 a redelivery for a terminal Order keeps its state and executes no business effect")
    void terminalOrderIsNotReprocessed(OrderStatus status) {
        Order order = fixture.createOrder(CUSTOMER_A, key(1), "A-1-1");
        Order terminal = switch (status) {
            case CONFIRMED -> order.startPayment(NOW).recordPaymentOutcome(Order.PaymentOutcome.APPROVED).confirm(NOW);
            case REJECTED -> order.startPayment(NOW).recordPaymentOutcome(Order.PaymentOutcome.DECLINED).reject();
            case FAILED -> order.failProcessing(NOW);
            default -> order.expire(order.reservation().expiresAt());
        };
        fixture.store.putOrder(new OrderRecord(terminal, NOW, NOW, NOW));
        int audits = fixture.store.audits().size();

        MessageDisposition disposition = fixture.process(order.orderId());

        assertThat(disposition).isEqualTo(MessageDisposition.delete(DispositionReason.ALREADY_TERMINAL));
        assertThat(fixture.order(order.orderId()).order()).isEqualTo(terminal);
        assertThat(fixture.store.audits()).hasSize(audits);
        assertThat(fixture.gateway.authorizations()).isEmpty();
    }

    @Test
    @DisplayName("AC-023 AC-024 ERR-004 BR-020 a duplicate while the lease of another consumer is in force is postponed without a second attempt")
    void duplicateWithForeignLeaseIsPostponed() {
        Order order = fixture.createOrder(CUSTOMER_A, key(1), "A-1-1");
        Order started = order.startPayment(NOW);
        fixture.store.putOrder(new OrderRecord(started, NOW, NOW, NOW, new PaymentLease("worker-2/7", NOW.plusSeconds(45))));
        fixture.store.setTicket(EVENT_ID, "A-1-1", TicketState.PENDING_CONFIRMATION, order.orderId());

        MessageDisposition disposition = fixture.process(order.orderId());

        assertThat(disposition).isEqualTo(MessageDisposition.postponeUntil(NOW.plusSeconds(45),
                DispositionReason.LEASE_HELD_ELSEWHERE));
        assertThat(fixture.gateway.authorizations()).isEmpty();
        assertThat(fixture.order(order.orderId()).order().paymentAttempt()).isEqualTo(started.paymentAttempt());
    }

    @Test
    @DisplayName("ADR-029 ALT-004 a lease that cannot be claimed is re-read and the delivery is postponed to the new lease end")
    void lostLeaseClaimIsPostponed() {
        Order order = fixture.createOrder(CUSTOMER_A, key(1), "A-1-1");
        Order started = order.startPayment(NOW);
        fixture.store.putOrder(new OrderRecord(started, NOW, NOW, NOW, new PaymentLease("worker-2/7", NOW.minusSeconds(1))));
        fixture.store.setTicket(EVENT_ID, "A-1-1", TicketState.PENDING_CONFIRMATION, order.orderId());
        fixture.store.beforeNext(Operation.CLAIM_LEASE, () -> fixture.store.putOrder(new OrderRecord(started, NOW, NOW, NOW,
                new PaymentLease("worker-3/1", NOW.plusSeconds(40)))));

        MessageDisposition disposition = fixture.process(order.orderId());

        assertThat(disposition).isEqualTo(MessageDisposition.postponeUntil(NOW.plusSeconds(40),
                DispositionReason.LEASE_HELD_ELSEWHERE));
        assertThat(fixture.gateway.authorizations()).isEmpty();
    }

    @Test
    @DisplayName("ERR-005 messaging 5.1 rules 1, 2 and 4: unreadable and unknown Orders are poison, a quarantined Order is deleted untouched")
    void poisonAndQuarantinedMessages() {
        MessageDisposition unreadable = value(fixture.processing.process(
                ProcessOrderCommand.unreadable("unknown schemaVersion", new Delivery(1, false))));
        MessageDisposition missing = fixture.process("00000000-0000-4000-8000-999999999999");
        Order order = fixture.createOrder(CUSTOMER_A, key(1), "A-1-1");
        fixture.store.putOrder(new OrderRecord(order.quarantine(NOW, "manual"), NOW, NOW, NOW));

        MessageDisposition quarantined = fixture.process(order.orderId());

        assertThat(unreadable).isEqualTo(MessageDisposition.poison(DispositionReason.UNREADABLE_MESSAGE));
        assertThat(missing).isEqualTo(MessageDisposition.poison(DispositionReason.ENTITY_NOT_FOUND));
        assertThat(quarantined).isEqualTo(MessageDisposition.delete(DispositionReason.QUARANTINED));
        assertThat(fixture.states(order)).containsOnly(TicketState.RESERVED);
        assertThat(fixture.gateway.authorizations()).isEmpty();
    }

    // ------------------------------------------------------------------ quarantine (rule 15)

    @Test
    @DisplayName("ADR-025 messaging 5.1 rule 15 a payment start cancelled by a Ticket condition quarantines the Order and keeps the lock")
    void ticketConditionOnPaymentStartQuarantines() {
        Order order = fixture.createOrder(CUSTOMER_A, key(1), "A-1-1", "A-1-2");
        fixture.store.setTicket(EVENT_ID, "A-1-2", TicketState.RESERVED, "someone-else");

        MessageDisposition disposition = fixture.process(order.orderId());

        OrderRecord stored = fixture.order(order.orderId());
        assertThat(disposition).isEqualTo(MessageDisposition.delete(DispositionReason.QUARANTINE_APPLIED));
        assertThat(stored.order().status()).isEqualTo(OrderStatus.CREATED);
        assertThat(stored.order().quarantineReason()).isEqualTo(OrderQuarantine.ON_PAYMENT_START);
        assertThat(stored.order().paymentAttempt()).isNull();
        assertThat(fixture.ticket("A-1-1").state()).isEqualTo(TicketState.RESERVED);
        assertThat(fixture.ticket("A-1-1").orderId()).isEqualTo(order.orderId());
        assertThat(fixture.store.activeLock(CUSTOMER_A, EVENT_ID)).contains(order.orderId());
        AuditRecord quarantined = audit(order.orderId(), AuditCode.ORDER_QUARANTINED);
        assertThat(quarantined.actor().type()).isEqualTo(ActorType.WORKER);
        assertThat(quarantined.cause()).isEqualTo(OrderQuarantine.ON_PAYMENT_START);
        assertThat(fixture.gateway.authorizations()).isEmpty();
        int shard = ShardingPolicy.shard(order.orderId(), ShardingPolicy.DEPLOYED.reservationShards());
        fixture.clock.set(order.reservation().expiresAt().plusSeconds(1));
        assertThat(value(fixture.store.findDueReservations(shard, fixture.clock.now()).collectList())).isEmpty();
        assertThat(OrderViews.of(stored).status()).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    @DisplayName("ADR-025 rule 15 a confirmation cancelled by a Ticket condition while the Order guard holds quarantines the Order")
    void ticketConditionOnConfirmationQuarantines() {
        Order order = fixture.createOrder(CUSTOMER_A, key(1), "A-1-1", "A-1-2");
        fixture.store.beforeNext(Operation.CONFIRM, () -> fixture.store.removeTicket(EVENT_ID, "A-1-2"));

        MessageDisposition disposition = fixture.process(order.orderId());

        OrderRecord stored = fixture.order(order.orderId());
        assertThat(disposition).isEqualTo(MessageDisposition.delete(DispositionReason.QUARANTINE_APPLIED));
        assertThat(stored.order().quarantineReason()).isEqualTo(OrderQuarantine.ON_CONFIRMATION);
        assertThat(fixture.ticket("A-1-1").state()).isEqualTo(TicketState.PENDING_CONFIRMATION);
    }

    @Test
    @DisplayName("ADR-025 rule 15 a rejection cancelled by a Ticket condition quarantines the Order with the rejection reason")
    void ticketConditionOnRejectionQuarantines() {
        Order order = fixture.createOrder(CUSTOMER_A, key(1), "A-1-1");
        fixture.gateway.scriptAuthorizations(new AuthorizationOutcome.Declined("ref", "DECLINED"));
        fixture.store.beforeNext(Operation.CLOSE, () ->
                fixture.store.setTicket(EVENT_ID, "A-1-1", TicketState.SOLD, order.orderId()));

        MessageDisposition disposition = fixture.process(order.orderId());

        assertThat(disposition).isEqualTo(MessageDisposition.delete(DispositionReason.QUARANTINE_APPLIED));
        assertThat(fixture.order(order.orderId()).order().quarantineReason()).isEqualTo(OrderQuarantine.ON_REJECTION);
        assertThat(fixture.ticket("A-1-1").state()).isEqualTo(TicketState.SOLD);
    }

    @Test
    @DisplayName("ADR-025 rule 15 a last-reception failure cancelled by a Ticket condition quarantines the Order and deletes the message")
    void ticketConditionOnExhaustionQuarantines() {
        Order order = fixture.createOrder(CUSTOMER_A, key(1), "A-1-1");
        fixture.gateway.scriptAuthorizations(new AuthorizationOutcome.DependencyUnavailable("timeout"));
        fixture.store.beforeNext(Operation.CLOSE, () -> fixture.store.removeTicket(EVENT_ID, "A-1-1"));

        MessageDisposition disposition = fixture.process(order.orderId(), 5, true);

        assertThat(disposition).isEqualTo(MessageDisposition.delete(DispositionReason.QUARANTINE_APPLIED));
        assertThat(fixture.order(order.orderId()).order().quarantineReason())
                .isEqualTo(OrderQuarantine.ON_PROCESSING_FAILURE);
    }

    // ------------------------------------------------------------------ store failures

    @Test
    @DisplayName("ERR-005 ADR-029 a store failure is transient: the message is retried and nothing changes")
    void storeFailureIsTransient() {
        Order order = fixture.createOrder(CUSTOMER_A, key(1), "A-1-1");
        fixture.store.failNext(Operation.START_PAYMENT, 1);

        MessageDisposition disposition = fixture.process(order.orderId(), 2, false);

        assertThat(disposition).isEqualTo(MessageDisposition.retry(DispositionReason.TRANSIENT_FAILURE));
        assertThat(fixture.order(order.orderId()).order().paymentAttempt()).isNull();
        assertThat(fixture.states(order)).containsOnly(TicketState.RESERVED);
    }

    @Test
    @DisplayName("ADR-029 FG-003 a store failure on the last reception closes the Order FAILED without reversal when no PaymentAttempt exists")
    void storeFailureOnLastReceptionFailsWithoutAttempt() {
        Order order = fixture.createOrder(CUSTOMER_A, key(1), "A-1-1");
        fixture.store.failNext(Operation.START_PAYMENT, 1);

        MessageDisposition disposition = fixture.process(order.orderId(), 5, true);

        OrderRecord stored = fixture.order(order.orderId());
        assertThat(disposition).isEqualTo(MessageDisposition.retry(DispositionReason.EXHAUSTED));
        assertThat(stored.order().status()).isEqualTo(OrderStatus.FAILED);
        assertThat(stored.order().reversalPlan()).isNull();
        assertThat(fixture.states(order)).containsOnly(TicketState.AVAILABLE);
        assertThat(audit(order.orderId(), AuditCode.PROCESSING_FAILED).ticketFrom()).isEqualTo(TicketState.RESERVED);
    }

    @Test
    @DisplayName("ADR-029 RISK-014 when the store stays unavailable on the last reception the message moves to the DLQ untouched")
    void unavailableStoreOnLastReception() {
        Order order = fixture.createOrder(CUSTOMER_A, key(1), "A-1-1");
        fixture.store.failNext(Operation.FIND_ORDER, 2);

        MessageDisposition disposition = fixture.process(order.orderId(), 5, true);

        assertThat(disposition).isEqualTo(MessageDisposition.retry(DispositionReason.TRANSIENT_FAILURE));
        assertThat(fixture.order(order.orderId()).order().status()).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    @DisplayName("ADR-035 a persistent transactional conflict is transient and never marks the Order")
    void conflictIsTransient() {
        Order order = fixture.createOrder(CUSTOMER_A, key(1), "A-1-1");
        fixture.store.script(Operation.START_PAYMENT, com.nequi.ticketing.application.port.out.TransactionOutcome.conflict());

        MessageDisposition disposition = fixture.process(order.orderId());

        assertThat(disposition).isEqualTo(MessageDisposition.retry(DispositionReason.TRANSIENT_FAILURE));
        assertThat(fixture.order(order.orderId()).order().paymentAttempt()).isNull();
    }

    @Test
    @DisplayName("ADR-008 point 4 when the authorization deadline has already passed the provider is not called")
    void deadlineReachedSkipsTheProvider() {
        Order order = fixture.createOrder(CUSTOMER_A, key(1), "A-1-1");
        Order started = order.startPayment(NOW);
        Instant late = order.reservation().expiresAt().minusSeconds(1);
        fixture.store.putOrder(new OrderRecord(started, NOW, NOW, NOW, new PaymentLease("worker-2/1", late.minusSeconds(1))));
        fixture.store.setTicket(EVENT_ID, "A-1-1", TicketState.PENDING_CONFIRMATION, order.orderId());
        fixture.clock.set(late);

        MessageDisposition disposition = fixture.process(order.orderId(), 3, false);

        assertThat(disposition).isEqualTo(MessageDisposition.retry(DispositionReason.TRANSIENT_FAILURE));
        assertThat(fixture.gateway.authorizations()).isEmpty();
        assertThat(fixture.order(order.orderId()).paymentLease().owner()).startsWith(WorkerFixture.WORKER + "/");
    }

    @Test
    @DisplayName("FR-016 two Orders of different customers are processed independently")
    void independentOrders() {
        Order first = fixture.createOrder(CUSTOMER_A, key(1), "A-1-1");
        Order second = fixture.createOrder(CUSTOMER_B, key(2), "A-1-2");
        fixture.gateway.scriptAuthorizations(new AuthorizationOutcome.Approved("r1"),
                new AuthorizationOutcome.Declined("r2", "DECLINED"));

        List<MessageDisposition> dispositions = new ArrayList<>();
        dispositions.add(fixture.process(first.orderId()));
        dispositions.add(fixture.process(second.orderId()));

        assertThat(dispositions).extracting(MessageDisposition::reason)
                .containsExactly(DispositionReason.CONFIRMED, DispositionReason.REJECTED);
    }

    private AuditRecord audit(String orderId, AuditCode code) {
        return fixture.store.audits().stream()
                .filter(record -> orderId.equals(record.orderId()) && record.code() == code)
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("no " + code + " audit for " + orderId));
    }
}
