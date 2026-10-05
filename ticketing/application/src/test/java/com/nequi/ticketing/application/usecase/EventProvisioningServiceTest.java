package com.nequi.ticketing.application.usecase;

import static com.nequi.ticketing.application.usecase.Rejections.value;
import static com.nequi.ticketing.application.usecase.WorkerFixture.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nequi.ticketing.application.error.RequestRejectedException;
import com.nequi.ticketing.application.port.in.AvailabilityQuery;
import com.nequi.ticketing.application.port.in.Delivery;
import com.nequi.ticketing.application.port.in.DispositionReason;
import com.nequi.ticketing.application.port.in.ListEventsQuery;
import com.nequi.ticketing.application.port.in.MessageDisposition;
import com.nequi.ticketing.application.port.in.ProvisionEventCommand;
import com.nequi.ticketing.application.port.in.ProvisioningStatusView;
import com.nequi.ticketing.application.port.out.EventCatalog;
import com.nequi.ticketing.application.port.out.FailedItem;
import com.nequi.ticketing.application.port.out.InventoryVerification;
import com.nequi.ticketing.application.port.out.ItemFailure;
import com.nequi.ticketing.application.port.out.ProvisioningSnapshot;
import com.nequi.ticketing.application.port.out.TicketInventory;
import com.nequi.ticketing.application.port.out.TransactionOutcome;
import com.nequi.ticketing.application.testdouble.InMemoryTicketingStore.Operation;
import com.nequi.ticketing.domain.audit.AuditRecord;
import com.nequi.ticketing.domain.audit.AuditRecord.AuditCode;
import com.nequi.ticketing.domain.error.DomainErrorCode;
import com.nequi.ticketing.domain.event.Event;
import com.nequi.ticketing.domain.event.Event.ProvisioningStatus;
import com.nequi.ticketing.domain.event.InventoryLimits;
import com.nequi.ticketing.domain.ticket.Ticket;
import com.nequi.ticketing.domain.ticket.Ticket.TicketState;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class EventProvisioningServiceTest {

    private static final String EVENT = "10000000-0000-4000-8000-000000000077";
    private static final Instant STARTS_AT = NOW.plus(Duration.ofDays(20));

    private WorkerFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new WorkerFixture();
    }

    private Event seedProvisioning() {
        return fixture.store.seedProvisioningEvent(EVENT, STARTS_AT, ApiFixture.DEFINITION, NOW);
    }

    /** Batch size 10: the 25 Tickets of the definition form three batches (10, 10, 5). */
    private EventProvisioningService smallBatches() {
        WorkerUseCaseSettings deployed = fixture.settings;
        WorkerUseCaseSettings small = new WorkerUseCaseSettings(deployed.workerId(), deployed.paymentLeaseDuration(),
                deployed.authorizationMargin(), deployed.provisioningLeaseDuration(),
                new InventoryLimits(50_000, 100, 2_000, 1_000, 500, 10), deployed.stalledProvisioningThreshold(),
                deployed.maximumProvisioningRepublications(), deployed.republishAge(), 16, 8, 4, 2, 5,
                deployed.orderRules(), deployed.reversalSchedule(), deployed.maximumVerificationRepairs(),
                deployed.sharding());
        return new EventProvisioningService(fixture.store, fixture.store, fixture.clock, small);
    }

    private MessageDisposition provision(EventProvisioningService service, boolean last) {
        return value(service.provision(ProvisionEventCommand.readable(EVENT, WorkerFixture.CORRELATION,
                new Delivery(last ? 5 : 1, last), null)));
    }

    @Test
    @DisplayName("AC-001 AC-013 FR-001 BR-016 BR-021 VAL-009 ST-012 provisioning writes exactly capacity Tickets, complimentary ones included, then enables")
    void provisionsAndEnables() {
        seedProvisioning();
        List<Integer> progress = new CopyOnWriteArrayList<>();

        MessageDisposition disposition = value(fixture.provisioning.provision(ProvisionEventCommand.readable(
                EVENT, WorkerFixture.CORRELATION, new Delivery(1, false), progress::add)));

        ProvisioningSnapshot snapshot = fixture.store.snapshot(EVENT).orElseThrow();
        List<Ticket> tickets = fixture.store.tickets(EVENT);
        assertThat(disposition).isEqualTo(MessageDisposition.delete(DispositionReason.ENABLED));
        assertThat(snapshot.event().provisioningStatus()).isEqualTo(ProvisioningStatus.ENABLED);
        assertThat(snapshot.enabledAt()).isEqualTo(NOW);
        assertThat(snapshot.leaseOwner()).isNull();
        assertThat(tickets).hasSize(25);
        assertThat(tickets).filteredOn(ticket -> ticket.state() == TicketState.COMPLIMENTARY)
                .extracting(Ticket::ticketId).containsExactly("B-1-5");
        assertThat(tickets).filteredOn(ticket -> ticket.state() == TicketState.AVAILABLE).hasSize(24);
        assertThat(tickets).extracting(Ticket::ticketId).contains("A-1-1", "A-2-10", "B-1-4");
        assertThat(progress).containsExactly(1);
        AuditRecord enabled = fixture.store.audits().getLast();
        assertThat(enabled.code()).isEqualTo(AuditCode.EVENT_ENABLED);
        assertThat(enabled.transitionIds()).containsExactly("ST-012");
        assertThat(enabled.capacity()).isEqualTo(25);
        assertThat(enabled.availableCount()).isEqualTo(24);
        assertThat(enabled.complimentaryCount()).isEqualTo(1);
        assertThat(enabled.actor().id()).isEqualTo(WorkerFixture.WORKER);
    }

    @Test
    @DisplayName("ADR-024 FR-001 provisioning is resumed from provisionedBatches and checks the lease before every batch")
    void resumesFromProvisionedBatches() {
        Event event = seedProvisioning();
        fixture.store.seedSnapshot(new ProvisioningSnapshot(event, NOW, 2, null, null, "worker-9/1", NOW.minusSeconds(1),
                NOW.minusSeconds(61), 0, null));
        List<Ticket> firstTwenty = event.inventoryDefinition().validate(25, InventoryLimits.DEPLOYED).tickets().stream()
                .limit(20).map(seed -> Ticket.provision(EVENT, seed)).toList();
        fixture.store.writeBatch(event, firstTwenty).block(Duration.ofSeconds(5));
        int writesBefore = fixture.store.writtenBatches().size();

        MessageDisposition disposition = provision(smallBatches(), false);

        assertThat(disposition).isEqualTo(MessageDisposition.delete(DispositionReason.ENABLED));
        List<List<String>> batches = fixture.store.writtenBatches();
        assertThat(batches.subList(writesBefore, batches.size())).hasSize(1);
        assertThat(batches.getLast()).hasSize(5);
        assertThat(fixture.store.calls(Operation.RECORD_PROGRESS)).isEqualTo(1);
        assertThat(fixture.store.snapshot(EVENT).orElseThrow().provisionedBatches()).isEqualTo(2);
    }

    @Test
    @DisplayName("ADR-024 rule 6 RISK-016 when the check before a batch fails the worker stops without writing and re-evaluates")
    void lostLeaseStopsBeforeWriting() {
        Event event = seedProvisioning();
        EventProvisioningService service = smallBatches();
        fixture.store.beforeNext(Operation.WRITE_BATCH, () -> fixture.store.seedSnapshot(new ProvisioningSnapshot(
                event, NOW, 1, null, null, "worker-9/1", NOW.plusSeconds(60), NOW, 0, null)));

        MessageDisposition disposition = provision(service, false);

        assertThat(disposition).isEqualTo(MessageDisposition.postponeUntil(NOW.plusSeconds(60),
                DispositionReason.LEASE_HELD_ELSEWHERE));
        assertThat(fixture.store.writtenBatches()).hasSize(1);
        assertThat(fixture.store.snapshot(EVENT).orElseThrow().event().provisioningStatus())
                .isEqualTo(ProvisioningStatus.PROVISIONING);
    }

    @Test
    @DisplayName("ADR-024 VAL-009 FG-002 missing Tickets found by the verification are rewritten and verified again before enabling")
    void repairsMissingTickets() {
        seedProvisioning();
        fixture.store.beforeNext(Operation.VERIFY, () -> fixture.store.removeTicket(EVENT, "A-2-7"));

        MessageDisposition disposition = fixture.provision(EVENT);

        assertThat(disposition).isEqualTo(MessageDisposition.delete(DispositionReason.ENABLED));
        assertThat(fixture.store.writtenBatches().getLast()).containsExactly("A-2-7");
        assertThat(fixture.store.calls(Operation.VERIFY)).isEqualTo(2);
        assertThat(fixture.store.tickets(EVENT)).hasSize(25);
    }

    @Test
    @DisplayName("ADR-024 a verification that keeps failing after three repairs is transient; on the last reception the Event is FAILED")
    void persistentVerificationFailure() {
        Event event = seedProvisioning();
        TicketInventory inventory = mock(TicketInventory.class);
        when(inventory.writeBatch(any(), any())).thenReturn(Mono.empty());
        when(inventory.verify(any(), any())).thenReturn(Mono.just(new InventoryVerification(24, List.of("A-1-1"))));
        EventProvisioningService service = new EventProvisioningService(fixture.store, inventory, fixture.clock,
                fixture.settings);

        MessageDisposition retried = provision(service, false);
        fixture.store.seedSnapshot(new ProvisioningSnapshot(event, NOW, 0, null, null, null, null, NOW, 0, null));
        MessageDisposition exhausted = provision(service, true);

        assertThat(retried).isEqualTo(MessageDisposition.retry(DispositionReason.TRANSIENT_FAILURE));
        assertThat(exhausted).isEqualTo(MessageDisposition.retry(DispositionReason.EXHAUSTED));
        assertThat(fixture.store.snapshot(EVENT).orElseThrow().event().provisioningStatus())
                .isEqualTo(ProvisioningStatus.FAILED);
    }

    @Test
    @DisplayName("AC-040 ERR-018 ST-013 a transient failure on the last reception marks the Event FAILED: never visible nor sellable, FAILED for the ADMIN")
    void lastReceptionFailureMarksFailed() {
        seedProvisioning();
        fixture.store.failNext(Operation.WRITE_BATCH, 1);

        MessageDisposition disposition = provision(fixture.provisioning, true);

        ProvisioningSnapshot snapshot = fixture.store.snapshot(EVENT).orElseThrow();
        assertThat(disposition).isEqualTo(MessageDisposition.retry(DispositionReason.EXHAUSTED));
        assertThat(snapshot.event().provisioningStatus()).isEqualTo(ProvisioningStatus.FAILED);
        assertThat(snapshot.failedAt()).isEqualTo(NOW);
        assertThat(snapshot.leaseOwner()).isNull();
        AuditRecord audit = fixture.store.audits().getLast();
        assertThat(audit.code()).isEqualTo(AuditCode.EVENT_PROVISIONING_FAILED);
        assertThat(audit.cause()).isEqualTo("PROVISIONING_FAILED");
        assertThat(audit.transitionIds()).containsExactly("ST-013");

        ApiFixture api = new ApiFixture();
        api.store.seedSnapshot(snapshot);
        ProvisioningStatusView status = value(api.eventManagement.getProvisioningStatus(EVENT));
        assertThat(status.provisioningStatus()).isEqualTo(ProvisioningStatus.FAILED);
        assertThat(status.failureCause()).isEqualTo(ProvisioningStatusView.FailureCause.PROVISIONING_FAILED);
        assertThat(value(api.catalog.listEvents(new ListEventsQuery(null, null))).items()).isEmpty();
        Rejections.rejected(api.catalog.getAvailability(new AvailabilityQuery(EVENT, null, null, null)),
                DomainErrorCode.EVENT_NOT_FOUND);
        RequestRejectedException purchase = Rejections.rejected(api.purchases.startPurchase(
                new com.nequi.ticketing.application.port.in.StartPurchaseCommand(WorkerFixture.CUSTOMER_A, EVENT,
                        List.of("A-1-1"), ApiFixture.key(1), "c")), DomainErrorCode.EVENT_NOT_FOUND);
        assertThat(purchase.code()).isEqualTo(DomainErrorCode.EVENT_NOT_FOUND);
    }

    @Test
    @DisplayName("ALT-004 messaging 5.2 rule 9 a transient failure with receptions left keeps the message for backoff")
    void transientFailureIsRetried() {
        seedProvisioning();
        fixture.store.failNext(Operation.VERIFY, 1);

        MessageDisposition disposition = provision(fixture.provisioning, false);

        assertThat(disposition).isEqualTo(MessageDisposition.retry(DispositionReason.TRANSIENT_FAILURE));
        assertThat(fixture.store.snapshot(EVENT).orElseThrow().event().provisioningStatus())
                .isEqualTo(ProvisioningStatus.PROVISIONING);
    }

    @Test
    @DisplayName("FR-017 messaging 5.2 rules 1 to 4: unreadable and unknown are poison, terminal is deleted, a foreign lease postpones")
    void messageRules() {
        MessageDisposition unreadable = value(fixture.provisioning.provision(
                ProvisionEventCommand.unreadable("unknown messageType", new Delivery(1, false))));
        MessageDisposition missing = fixture.provision("10000000-0000-4000-8000-000000009999");
        Event event = seedProvisioning();
        fixture.store.seedSnapshot(new ProvisioningSnapshot(event, NOW, 0, null, null, "worker-9/3", NOW.plusSeconds(30),
                NOW, 0, null));
        MessageDisposition leased = fixture.provision(EVENT);
        fixture.store.seedSnapshot(new ProvisioningSnapshot(event.fail(), NOW, 0, null, NOW));
        MessageDisposition terminal = fixture.provision(EVENT);

        assertThat(unreadable).isEqualTo(MessageDisposition.poison(DispositionReason.UNREADABLE_MESSAGE));
        assertThat(missing).isEqualTo(MessageDisposition.poison(DispositionReason.ENTITY_NOT_FOUND));
        assertThat(leased).isEqualTo(MessageDisposition.postponeUntil(NOW.plusSeconds(30),
                DispositionReason.LEASE_HELD_ELSEWHERE));
        assertThat(terminal).isEqualTo(MessageDisposition.delete(DispositionReason.ALREADY_TERMINAL));
        assertThat(fixture.store.writtenBatches()).isEmpty();
    }

    @Test
    @DisplayName("ADR-024 an enablement cancelled because the lease was lost is re-evaluated; a conflict is transient")
    void enablementOutcomes() {
        Event event = seedProvisioning();
        fixture.store.beforeNext(Operation.ENABLE, () -> fixture.store.seedSnapshot(new ProvisioningSnapshot(
                event, NOW, 1, null, null, "worker-9/1", NOW.plusSeconds(60), NOW, 0, null)));

        MessageDisposition lost = fixture.provision(EVENT);

        assertThat(lost).isEqualTo(MessageDisposition.postponeUntil(NOW.plusSeconds(60),
                DispositionReason.LEASE_HELD_ELSEWHERE));

        fixture.store.seedSnapshot(new ProvisioningSnapshot(event, NOW, 1, null, null, null, null, NOW, 0, null));
        fixture.store.script(Operation.ENABLE, TransactionOutcome.conflict());
        assertThat(fixture.provision(EVENT)).isEqualTo(MessageDisposition.retry(DispositionReason.TRANSIENT_FAILURE));
    }

    @Test
    @DisplayName("ADR-024 rule 10 the last reception does not fail an Event whose lease belongs to another worker or that is already terminal")
    void lastReceptionGuards() {
        Event event = seedProvisioning();
        fixture.store.failNext(Operation.ACQUIRE_PROVISIONING_LEASE, 1);
        fixture.store.beforeNext(Operation.ACQUIRE_PROVISIONING_LEASE, () -> fixture.store.seedSnapshot(
                new ProvisioningSnapshot(event, NOW, 0, null, null, "worker-9/1", NOW.plusSeconds(50), NOW, 0, null)));

        assertThat(provision(fixture.provisioning, true)).isEqualTo(MessageDisposition.postponeUntil(
                NOW.plusSeconds(50), DispositionReason.LEASE_HELD_ELSEWHERE));

        fixture.store.seedSnapshot(new ProvisioningSnapshot(event, NOW, 0, null, null, null, null, NOW, 0, null));
        fixture.store.failNext(Operation.ACQUIRE_PROVISIONING_LEASE, 1);
        fixture.store.beforeNext(Operation.ACQUIRE_PROVISIONING_LEASE, () -> fixture.store.seedSnapshot(
                new ProvisioningSnapshot(event.enable(25), NOW, 1, NOW, null)));
        assertThat(provision(fixture.provisioning, true)).isEqualTo(MessageDisposition.delete(DispositionReason.ALREADY_TERMINAL));

        fixture.store.seedSnapshot(new ProvisioningSnapshot(event, NOW, 0, null, null, null, null, NOW, 0, null));
        fixture.store.failNext(Operation.WRITE_BATCH, 1);
        fixture.store.script(Operation.MARK_FAILED, TransactionOutcome.cancelled(List.of(ItemFailure.of(FailedItem.EVENT))));
        assertThat(provision(fixture.provisioning, true)).isEqualTo(MessageDisposition.retry(DispositionReason.TRANSIENT_FAILURE));

        fixture.store.seedSnapshot(new ProvisioningSnapshot(event, NOW, 0, null, null, null, null, NOW, 0, null));
        fixture.store.failNext(Operation.WRITE_BATCH, 1);
        fixture.store.beforeNext(Operation.WRITE_BATCH, () -> fixture.store.failNext(Operation.FIND_SNAPSHOT, 1));
        assertThat(provision(fixture.provisioning, true)).isEqualTo(MessageDisposition.retry(DispositionReason.TRANSIENT_FAILURE));

        fixture.store.seedSnapshot(new ProvisioningSnapshot(event, NOW, 0, null, null, null, null, NOW, 0, null));
        fixture.store.failNext(Operation.WRITE_BATCH, 1);
        List<MessageDisposition> outcomes = new ArrayList<>();
        outcomes.add(provision(fixture.provisioning, true));
        assertThat(outcomes).containsExactly(MessageDisposition.retry(DispositionReason.EXHAUSTED));
    }

    @Test
    @DisplayName("ERR-005 an Event that disappears before the last-reception re-read is poison")
    void vanishedEventOnLastReception() {
        seedProvisioning();
        fixture.store.failNext(Operation.WRITE_BATCH, 1);
        fixture.store.beforeNext(Operation.WRITE_BATCH, () -> fixture.store.removeEvent(EVENT));

        assertThat(provision(fixture.provisioning, true))
                .isEqualTo(MessageDisposition.poison(DispositionReason.ENTITY_NOT_FOUND));
    }

    @Test
    @DisplayName("ADR-024 a lease that cannot be taken is re-evaluated; endless contention is bounded and treated as transient")
    void boundedReevaluation() {
        Event event = seedProvisioning();
        EventCatalog catalog = mock(EventCatalog.class);
        when(catalog.findProvisioningSnapshot(EVENT)).thenReturn(Mono.just(
                new ProvisioningSnapshot(event, NOW, 0, null, null, null, null, NOW, 0, null)));
        when(catalog.acquireProvisioningLease(any(), any(), any(), any())).thenReturn(Mono.empty());
        EventProvisioningService service = new EventProvisioningService(catalog, fixture.store, fixture.clock,
                fixture.settings);

        assertThat(provision(service, false)).isEqualTo(MessageDisposition.retry(DispositionReason.TRANSIENT_FAILURE));
    }

    @Test
    @DisplayName("ADR-024 rule 6 a repair whose check fails stops without writing and re-evaluates")
    void repairStopsWhenTheCheckFails() {
        Event event = seedProvisioning();
        fixture.store.beforeNext(Operation.VERIFY, () -> {
            fixture.store.removeTicket(EVENT, "A-1-3");
            fixture.store.seedSnapshot(new ProvisioningSnapshot(event, NOW, 1, null, null, "worker-9/1",
                    NOW.plusSeconds(40), NOW, 0, null));
        });

        MessageDisposition disposition = fixture.provision(EVENT);

        assertThat(disposition).isEqualTo(MessageDisposition.postponeUntil(NOW.plusSeconds(40),
                DispositionReason.LEASE_HELD_ELSEWHERE));
        assertThat(fixture.store.ticket(EVENT, "A-1-3")).isNull();
    }
}
