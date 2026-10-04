package com.nequi.ticketing.application.usecase;

import static com.nequi.ticketing.application.usecase.ApiFixture.CORRELATION;
import static com.nequi.ticketing.application.usecase.ApiFixture.CUSTOMER_A;
import static com.nequi.ticketing.application.usecase.ApiFixture.EVENT_ID;
import static com.nequi.ticketing.application.usecase.ApiFixture.command;
import static com.nequi.ticketing.application.usecase.ApiFixture.key;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.nequi.ticketing.application.error.RequestRejectedException;
import com.nequi.ticketing.application.port.in.PurchaseResult;
import com.nequi.ticketing.application.port.in.StartPurchaseCommand;
import com.nequi.ticketing.application.port.out.EnqueueFailurePlan;
import com.nequi.ticketing.application.port.out.OrderRecord;
import com.nequi.ticketing.application.port.out.TransactionOutcome;
import com.nequi.ticketing.domain.audit.AuditRecord;
import com.nequi.ticketing.domain.audit.AuditRecord.Actor;
import com.nequi.ticketing.domain.audit.AuditRecord.ActorType;
import com.nequi.ticketing.domain.audit.AuditRecordBuilder;
import com.nequi.ticketing.domain.audit.AuditRecord.AuditCode;
import com.nequi.ticketing.domain.error.DomainErrorCode;
import com.nequi.ticketing.domain.order.Order;
import com.nequi.ticketing.domain.order.Order.OrderStatus;
import com.nequi.ticketing.domain.ticket.Ticket;
import com.nequi.ticketing.domain.ticket.Ticket.TicketState;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * ADR-038 deterministic concurrency mechanisms on the in-memory double: every request passes the
 * pre-transaction checks, the reservations are held at a non-blocking gate and then released together
 * on parallel workers, so they really contend for the same items.
 */
class PurchaseMechanismTest {

    private ApiFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new ApiFixture();
        fixture.seedEvent();
    }

    @RepeatedTest(5)
    @DisplayName("ADR-038/mechanism AC-007 ERR-001 FR-010 simultaneous purchases of the same ticket: one winner, N-1 rejections, nothing partial")
    void simultaneousPurchasesOfTheSameTicketHaveOneWinner() throws Exception {
        List<StartPurchaseCommand> commands = new ArrayList<>();
        for (int customer = 1; customer <= 8; customer++) {
            commands.add(new StartPurchaseCommand("customer-" + customer, EVENT_ID,
                    List.of("A-1-1", "A-2-" + customer), key(customer), CORRELATION));
        }

        List<Outcome> outcomes = runSimultaneously(commands);

        List<Outcome> winners = outcomes.stream().filter(Outcome::accepted).toList();
        assertThat(winners).hasSize(1);
        assertThat(outcomes).filteredOn(outcome -> !outcome.accepted())
                .hasSize(7)
                .allSatisfy(outcome -> assertThat(outcome.code()).isEqualTo(DomainErrorCode.TICKETS_UNAVAILABLE));
        assertNoOversellingAndNoPartialReservation();
        String winnerOrder = winners.getFirst().result().order().orderId();
        assertThat(fixture.store.ticket(EVENT_ID, "A-1-1").orderId()).isEqualTo(winnerOrder);
        assertThat(fixture.store.tickets(EVENT_ID)).filteredOn(ticket -> ticket.state() == TicketState.RESERVED).hasSize(2);
    }

    @RepeatedTest(5)
    @DisplayName("ADR-038/mechanism AC-007 VAL-004 overlapping ticket sets never oversell and never reserve partially")
    void overlappingSetsNeverOversell() throws Exception {
        List<StartPurchaseCommand> commands = new ArrayList<>();
        for (int customer = 1; customer <= 9; customer++) {
            commands.add(new StartPurchaseCommand("customer-" + customer, EVENT_ID,
                    List.of("A-1-" + customer, "A-1-" + (customer + 1)), key(customer), CORRELATION));
        }

        List<Outcome> outcomes = runSimultaneously(commands);

        assertThat(outcomes).filteredOn(Outcome::accepted).isNotEmpty();
        assertThat(outcomes).filteredOn(outcome -> !outcome.accepted())
                .allSatisfy(outcome -> assertThat(outcome.code()).isEqualTo(DomainErrorCode.TICKETS_UNAVAILABLE));
        assertNoOversellingAndNoPartialReservation();
        long reserved = fixture.store.tickets(EVENT_ID).stream().filter(ticket -> ticket.state() == TicketState.RESERVED).count();
        assertThat(reserved).isEqualTo(2L * outcomes.stream().filter(Outcome::accepted).count());
    }

    @RepeatedTest(5)
    @DisplayName("ADR-038/mechanism ADR-027 two simultaneous requests with the same key and content produce the same Order")
    void sameKeyAndContentProduceTheSameOrder() throws Exception {
        StartPurchaseCommand request = command(CUSTOMER_A, key(1), "A-1-1", "A-1-2");

        List<Outcome> outcomes = runSimultaneously(List.of(request, request));

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.accepted()).isTrue());
        assertThat(outcomes).extracting(outcome -> outcome.result().order().orderId()).containsOnly(
                outcomes.getFirst().result().order().orderId());
        assertThat(outcomes).extracting(outcome -> outcome.result().replayed()).containsExactlyInAnyOrder(false, true);
        assertThat(fixture.store.orders()).hasSize(1);
        assertNoOversellingAndNoPartialReservation();
    }

    @RepeatedTest(5)
    @DisplayName("ADR-038/mechanism ADR-027 same key with different content: one Order and IDEMPOTENCY_KEY_REUSED, never TICKETS_UNAVAILABLE")
    void sameKeyDifferentContentProducesOneOrderAndAReusedKey() throws Exception {
        List<Outcome> outcomes = runSimultaneously(List.of(
                command(CUSTOMER_A, key(1), "A-1-1"),
                command(CUSTOMER_A, key(1), "A-1-1", "A-1-2")));

        assertThat(outcomes).filteredOn(Outcome::accepted).hasSize(1);
        assertThat(outcomes).filteredOn(outcome -> !outcome.accepted())
                .singleElement()
                .satisfies(outcome -> assertThat(outcome.code()).isEqualTo(DomainErrorCode.IDEMPOTENCY_KEY_REUSED));
        assertThat(fixture.store.orders()).hasSize(1);
        assertNoOversellingAndNoPartialReservation();
    }

    @RepeatedTest(5)
    @DisplayName("ADR-038/mechanism AC-044 ADR-032 simultaneous purchases of the same CUSTOMER and Event create at most one Order")
    void simultaneousPurchasesOfTheSameCustomerCreateOneOrder() throws Exception {
        List<StartPurchaseCommand> commands = new ArrayList<>();
        for (int request = 1; request <= 5; request++) {
            commands.add(command(CUSTOMER_A, key(request), "A-2-" + request));
        }

        List<Outcome> outcomes = runSimultaneously(commands);

        assertThat(outcomes).filteredOn(Outcome::accepted).hasSize(1);
        assertThat(outcomes).filteredOn(outcome -> !outcome.accepted())
                .hasSize(4)
                .allSatisfy(outcome -> assertThat(outcome.code()).isEqualTo(DomainErrorCode.ACTIVE_ORDER_EXISTS));
        assertThat(fixture.store.orders()).hasSize(1);
        assertNoOversellingAndNoPartialReservation();
    }

    @Test
    @DisplayName("ADR-038/mechanism AC-045 ADR-032 after a terminal transition removes the lock a new purchase is possible")
    void terminalTransitionReleasesTheLock() throws Exception {
        List<Outcome> race = runSimultaneously(List.of(
                command(CUSTOMER_A, key(1), "A-2-1"), command(CUSTOMER_A, key(2), "A-2-2")));
        OrderRecord active = fixture.store.orders().getFirst();
        Order failed = active.order().failEnqueue();
        AuditRecord audit = new AuditRecordBuilder().code(AuditCode.ENQUEUE_FAILED).order(failed.orderId())
                .actor(new Actor(ActorType.SYSTEM, "test")).correlation(CORRELATION).occurredAt(ApiFixture.NOW).build();
        TransactionOutcome terminal = fixture.store.failEnqueue(
                new EnqueueFailurePlan(active.order(), failed, audit, ApiFixture.NOW)).block(Duration.ofSeconds(5));

        PurchaseResult again = Rejections.value(fixture.purchase(CUSTOMER_A, key(3), "A-2-3"));

        assertThat(race).filteredOn(Outcome::accepted).hasSize(1);
        assertThat(terminal).isInstanceOf(TransactionOutcome.Applied.class);
        assertThat(again.order().status()).isEqualTo(OrderStatus.CREATED);
        assertThat(fixture.store.activeLock(CUSTOMER_A, EVENT_ID)).contains(again.order().orderId());
    }

    // ------------------------------------------------------------------ harness

    private List<Outcome> runSimultaneously(List<StartPurchaseCommand> commands) throws Exception {
        fixture.store.holdReservations();
        int alreadyWaiting = fixture.store.waitingReservations();
        var pending = Flux.fromIterable(commands)
                .flatMap(command -> fixture.purchases.startPurchase(command)
                        .map(Outcome::ofResult)
                        .onErrorResume(RequestRejectedException.class, rejection -> Mono.just(Outcome.ofRejection(rejection)))
                        .subscribeOn(Schedulers.parallel()))
                .collectList()
                .toFuture();
        await().atMost(Duration.ofSeconds(5))
                .until(() -> fixture.store.waitingReservations() - alreadyWaiting == commands.size());
        fixture.store.releaseReservations();
        return pending.get(10, TimeUnit.SECONDS);
    }

    /** NFR-004 invariant: no Ticket in more than one Order, every created Order holds all its tickets. */
    private void assertNoOversellingAndNoPartialReservation() {
        Map<String, Ticket> byTicket = new HashMap<>();
        fixture.store.tickets(EVENT_ID).forEach(ticket -> byTicket.put(ticket.ticketId(), ticket));
        Set<String> createdOrders = fixture.store.orders().stream()
                .filter(record -> record.order().status() == OrderStatus.CREATED)
                .map(record -> record.order().orderId())
                .collect(Collectors.toSet());
        for (OrderRecord record : fixture.store.orders()) {
            for (String ticketId : record.order().ticketIds()) {
                Ticket ticket = byTicket.get(ticketId);
                assertThat(ticket.state()).isEqualTo(TicketState.RESERVED);
                assertThat(ticket.orderId()).isEqualTo(record.order().orderId());
            }
        }
        assertThat(byTicket.values()).filteredOn(ticket -> ticket.state() == TicketState.RESERVED)
                .allSatisfy(ticket -> assertThat(createdOrders).contains(ticket.orderId()));
    }

    private record Outcome(PurchaseResult result, DomainErrorCode code) {
        static Outcome ofResult(PurchaseResult result) {
            return new Outcome(result, null);
        }

        static Outcome ofRejection(RequestRejectedException rejection) {
            return new Outcome(null, rejection.code());
        }

        boolean accepted() {
            return result != null;
        }
    }
}
