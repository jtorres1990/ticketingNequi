package com.nequi.ticketing.application.usecase;

import static com.nequi.ticketing.application.usecase.WorkerFixture.CORRELATION;
import static com.nequi.ticketing.application.usecase.WorkerFixture.CUSTOMER_A;
import static com.nequi.ticketing.application.usecase.WorkerFixture.EVENT_ID;
import static com.nequi.ticketing.application.usecase.WorkerFixture.NOW;
import static com.nequi.ticketing.application.usecase.WorkerFixture.key;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.nequi.ticketing.application.port.in.CycleRequest;
import com.nequi.ticketing.application.port.in.CycleResult;
import com.nequi.ticketing.application.port.in.Delivery;
import com.nequi.ticketing.application.port.in.DispositionReason;
import com.nequi.ticketing.application.port.in.ItemOutcome;
import com.nequi.ticketing.application.port.in.MessageDisposition;
import com.nequi.ticketing.application.port.in.ProcessOrderCommand;
import com.nequi.ticketing.application.port.in.ProvisionEventCommand;
import com.nequi.ticketing.application.port.in.StartPurchaseCommand;
import com.nequi.ticketing.application.port.out.ProvisioningSnapshot;
import com.nequi.ticketing.application.testdouble.InMemoryTicketingStore.Operation;
import com.nequi.ticketing.domain.audit.AuditRecord.AuditCode;
import com.nequi.ticketing.domain.event.Event;
import com.nequi.ticketing.domain.event.Event.ProvisioningStatus;
import com.nequi.ticketing.domain.order.Order;
import com.nequi.ticketing.domain.order.Order.OrderStatus;
import com.nequi.ticketing.domain.ticket.Ticket;
import com.nequi.ticketing.domain.ticket.Ticket.TicketState;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * ADR-038 deterministic mechanisms of the {@code worker} role on the in-memory doubles: the
 * contending operations are held at non-blocking gates and released together on parallel workers so
 * that they really race for the same items.
 */
class WorkerMechanismTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private WorkerFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new WorkerFixture();
        fixture.seedEvent();
    }

    @RepeatedTest(5)
    @DisplayName("ADR-038/mechanism AC-023 AC-024 ERR-004 simultaneous deliveries of the same MSG-001 open a single PaymentAttempt and confirm once")
    void simultaneousDeliveriesOpenOnePaymentAttempt() throws Exception {
        Order order = fixture.createOrder(CUSTOMER_A, key(1), "A-1-1", "A-1-2");
        fixture.store.hold(Operation.START_PAYMENT);
        int deliveries = 6;

        CompletableFuture<List<MessageDisposition>> pending = Flux.range(1, deliveries)
                .flatMap(receive -> fixture.processing.process(ProcessOrderCommand.readable(order.orderId(), CORRELATION,
                        new Delivery(1, false))).subscribeOn(Schedulers.parallel()))
                .collectList()
                .toFuture();
        await().atMost(TIMEOUT).until(() -> fixture.store.waiting(Operation.START_PAYMENT) == deliveries);
        fixture.store.release(Operation.START_PAYMENT);
        List<MessageDisposition> dispositions = pending.get(10, TimeUnit.SECONDS);

        assertThat(dispositions).filteredOn(disposition -> disposition.reason() == DispositionReason.CONFIRMED).hasSize(1);
        assertThat(dispositions).filteredOn(disposition -> disposition.reason() != DispositionReason.CONFIRMED)
                .allSatisfy(disposition -> assertThat(disposition.reason()).isIn(
                        DispositionReason.LEASE_HELD_ELSEWHERE, DispositionReason.ALREADY_TERMINAL));
        assertThat(fixture.gateway.attemptIds()).containsExactly(order.orderId() + "-1");
        assertThat(fixture.gateway.authorizations()).hasSize(1);
        assertThat(countAudits(order.orderId(), AuditCode.PAYMENT_STARTED)).isEqualTo(1);
        assertThat(countAudits(order.orderId(), AuditCode.PAYMENT_APPROVED)).isEqualTo(1);
        assertThat(fixture.order(order.orderId()).order().status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(fixture.states(order)).containsOnly(TicketState.SOLD);

        assertThat(fixture.process(order.orderId(), 2, false))
                .isEqualTo(MessageDisposition.delete(DispositionReason.ALREADY_TERMINAL));
        assertThat(fixture.gateway.authorizations()).hasSize(1);
    }

    @RepeatedTest(10)
    @DisplayName("ADR-038/mechanism AC-050 ADR-008 confirm and expire released simultaneously: exactly one terminal transition, never a partial")
    void simultaneousConfirmAndExpireHaveOneWinner() throws Exception {
        Order order = fixture.createOrder(CUSTOMER_A, key(1), "A-1-1", "A-1-2");
        Instant expiresAt = order.reservation().expiresAt();
        fixture.gateway.onAuthorize(() -> fixture.clock.set(expiresAt.minusSeconds(1)));
        fixture.store.hold(Operation.CONFIRM);
        fixture.store.hold(Operation.CLOSE);

        CompletableFuture<MessageDisposition> consumer = fixture.processing.process(ProcessOrderCommand.readable(
                        order.orderId(), CORRELATION, new Delivery(1, false)))
                .subscribeOn(Schedulers.parallel()).toFuture();
        await().atMost(TIMEOUT).until(() -> fixture.store.waiting(Operation.CONFIRM) == 1);
        fixture.clock.set(expiresAt);
        CompletableFuture<CycleResult> expiration = fixture.expiration.expireDue(CycleRequest.of(CORRELATION))
                .subscribeOn(Schedulers.parallel()).toFuture();
        await().atMost(TIMEOUT).until(() -> fixture.store.waiting(Operation.CLOSE) == 1);
        fixture.store.release(Operation.CLOSE);
        fixture.store.release(Operation.CONFIRM);
        MessageDisposition disposition = consumer.get(10, TimeUnit.SECONDS);
        CycleResult cycle = expiration.get(10, TimeUnit.SECONDS);

        Order stored = fixture.order(order.orderId()).order();
        long confirmations = countAudits(order.orderId(), AuditCode.PAYMENT_APPROVED);
        long expirations = countAudits(order.orderId(), AuditCode.RESERVATION_EXPIRED);
        assertThat(confirmations + expirations).isEqualTo(1);
        if (stored.status() == OrderStatus.CONFIRMED) {
            assertThat(fixture.states(order)).containsOnly(TicketState.SOLD);
            assertThat(cycle.count(ItemOutcome.NOT_APPLICABLE)).isEqualTo(1);
            assertThat(disposition.reason()).isEqualTo(DispositionReason.CONFIRMED);
            assertThat(stored.reversalPlan()).isNull();
        } else {
            assertThat(stored.status()).isEqualTo(OrderStatus.EXPIRED);
            assertThat(fixture.states(order)).containsOnly(TicketState.AVAILABLE);
            assertThat(cycle.count(ItemOutcome.EXPIRED)).isEqualTo(1);
            assertThat(disposition.reason()).isEqualTo(DispositionReason.LATE_APPROVAL_RECORDED);
            assertThat(stored.reversalPending()).isTrue();
            assertThat(countAudits(order.orderId(), AuditCode.LATE_APPROVAL_NOT_APPLIED)).isEqualTo(1);
        }
        assertThat(fixture.store.activeLock(CUSTOMER_A, EVENT_ID)).isEmpty();
    }

    @Test
    @DisplayName("ADR-038/mechanism AC-019 ADR-008 confirm first then expire: the expiration finds nothing to do")
    void confirmThenExpire() {
        Order order = fixture.createOrder(CUSTOMER_A, key(1), "A-1-1");

        fixture.process(order.orderId());
        fixture.clock.set(order.reservation().expiresAt().plusSeconds(1));
        CycleResult cycle = fixture.expire();

        assertThat(cycle.total()).isZero();
        assertThat(fixture.order(order.orderId()).order().status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(fixture.states(order)).containsOnly(TicketState.SOLD);
    }

    @Test
    @DisplayName("ADR-038/mechanism AC-034 ERR-016 FG-003 expire first then approval: one reversal mark and exactly one cancellation")
    void expireThenApprovalReversesOnce() throws Exception {
        Order order = fixture.createOrder(CUSTOMER_A, key(1), "A-1-1");
        fixture.gateway.holdAuthorizations();

        CompletableFuture<MessageDisposition> consumer = fixture.processing.process(ProcessOrderCommand.readable(
                        order.orderId(), CORRELATION, new Delivery(1, false)))
                .subscribeOn(Schedulers.parallel()).toFuture();
        await().atMost(TIMEOUT).until(() -> fixture.gateway.waitingAuthorizations() == 1);
        fixture.clock.set(order.reservation().expiresAt().plusSeconds(2));
        CycleResult cycle = fixture.expire();
        fixture.gateway.releaseAuthorizations();
        MessageDisposition disposition = consumer.get(10, TimeUnit.SECONDS);
        fixture.reverse();
        fixture.clock.advance(Duration.ofHours(1));
        fixture.reverse();

        assertThat(cycle.count(ItemOutcome.EXPIRED)).isEqualTo(1);
        assertThat(disposition).isEqualTo(MessageDisposition.delete(DispositionReason.LATE_APPROVAL_RECORDED));
        assertThat(fixture.order(order.orderId()).order().status()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(fixture.gateway.cancellationCalls()).containsExactly(order.orderId() + "-1");
        assertThat(countAudits(order.orderId(), AuditCode.PAYMENT_REVERSAL_CONFIRMED)).isEqualTo(1);
    }

    @Test
    @DisplayName("ADR-038/mechanism ADR-024 RISK-016 a provisioning redelivery after enabling and reserving takes no lease and modifies no Ticket")
    void provisioningRedeliveryAfterEnablement() {
        String eventId = "10000000-0000-4000-8000-000000000055";
        fixture.store.seedProvisioningEvent(eventId, NOW.plus(Duration.ofDays(9)), ApiFixture.DEFINITION, NOW);
        assertThat(fixture.provision(eventId)).isEqualTo(MessageDisposition.delete(DispositionReason.ENABLED));
        Rejections.value(fixture.purchases.startPurchase(new StartPurchaseCommand(
                CUSTOMER_A, eventId, List.of("A-1-1"), key(9), CORRELATION)));
        int acquisitions = fixture.store.calls(Operation.ACQUIRE_PROVISIONING_LEASE);
        int writes = fixture.store.writtenBatches().size();
        List<Ticket> before = fixture.store.tickets(eventId);

        MessageDisposition redelivery = fixture.provision(eventId, 2, false);

        assertThat(redelivery).isEqualTo(MessageDisposition.delete(DispositionReason.ALREADY_TERMINAL));
        assertThat(fixture.store.calls(Operation.ACQUIRE_PROVISIONING_LEASE)).isEqualTo(acquisitions);
        assertThat(fixture.store.writtenBatches()).hasSize(writes);
        assertThat(fixture.store.tickets(eventId)).containsExactlyInAnyOrderElementsOf(before);
        assertThat(fixture.store.ticket(eventId, "A-1-1").state()).isEqualTo(TicketState.RESERVED);
    }

    @Test
    @DisplayName("ADR-038/mechanism ADR-024 an expired lease of another worker is reclaimed and the provisioning completes")
    void expiredForeignLeaseIsReclaimed() {
        String eventId = "10000000-0000-4000-8000-000000000056";
        Event event = fixture.store.seedProvisioningEvent(eventId, NOW.plus(Duration.ofDays(9)), ApiFixture.DEFINITION, NOW);
        fixture.store.seedSnapshot(new ProvisioningSnapshot(event, NOW, 0, null, null, "worker-9/4",
                NOW.minusMillis(1), NOW.minusSeconds(61), 0, null));

        MessageDisposition disposition = fixture.provision(eventId);

        assertThat(disposition).isEqualTo(MessageDisposition.delete(DispositionReason.ENABLED));
        assertThat(fixture.store.snapshot(eventId).orElseThrow().event().provisioningStatus())
                .isEqualTo(ProvisioningStatus.ENABLED);
    }

    @RepeatedTest(5)
    @DisplayName("ADR-038/mechanism ADR-027 simultaneous deliveries of the same MSG-002: one lease owner, the others postponed, one enablement")
    void simultaneousProvisioningDeliveries() throws Exception {
        String eventId = "10000000-0000-4000-8000-000000000057";
        fixture.store.seedProvisioningEvent(eventId, NOW.plus(Duration.ofDays(9)), ApiFixture.DEFINITION, NOW);
        fixture.store.hold(Operation.ACQUIRE_PROVISIONING_LEASE);
        int deliveries = 4;

        CompletableFuture<List<MessageDisposition>> pending = Flux.fromStream(IntStream.rangeClosed(1, deliveries).boxed())
                .flatMap(receive -> fixture.provisioning.provision(ProvisionEventCommand.readable(eventId, CORRELATION,
                        new Delivery(1, false), null)).subscribeOn(Schedulers.parallel()))
                .collectList()
                .toFuture();
        await().atMost(TIMEOUT).until(() -> fixture.store.waiting(Operation.ACQUIRE_PROVISIONING_LEASE) == deliveries);
        fixture.store.release(Operation.ACQUIRE_PROVISIONING_LEASE);
        List<MessageDisposition> dispositions = pending.get(10, TimeUnit.SECONDS);

        assertThat(dispositions).filteredOn(disposition -> disposition.reason() == DispositionReason.ENABLED).hasSize(1);
        assertThat(dispositions).filteredOn(disposition -> disposition.reason() != DispositionReason.ENABLED)
                .allSatisfy(disposition -> assertThat(disposition.reason()).isIn(
                        DispositionReason.LEASE_HELD_ELSEWHERE, DispositionReason.ALREADY_TERMINAL));
        assertThat(fixture.store.tickets(eventId)).hasSize(25);
        assertThat(fixture.store.audits()).filteredOn(audit -> eventId.equals(audit.eventId())
                && audit.code() == AuditCode.EVENT_ENABLED).hasSize(1);
    }

    private long countAudits(String orderId, AuditCode code) {
        return fixture.store.audits().stream()
                .filter(audit -> orderId.equals(audit.orderId()) && audit.code() == code)
                .count();
    }
}
