package com.nequi.ticketing.infrastructure.adapter.out.dynamodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nequi.ticketing.application.port.out.AvailableTicket;
import com.nequi.ticketing.application.port.out.AvailableTicketPage;
import com.nequi.ticketing.application.port.out.ClosurePlan;
import com.nequi.ticketing.application.port.out.ConfirmationPlan;
import com.nequi.ticketing.application.port.out.EnabledEventPage;
import com.nequi.ticketing.application.port.out.EnablementPlan;
import com.nequi.ticketing.application.port.out.EnqueueFailurePlan;
import com.nequi.ticketing.application.port.out.FailedItem;
import com.nequi.ticketing.application.port.out.IdempotencyRecord;
import com.nequi.ticketing.application.port.out.InventoryVerification;
import com.nequi.ticketing.application.port.out.ItemFailure;
import com.nequi.ticketing.application.port.out.LateApprovalPlan;
import com.nequi.ticketing.application.port.out.NewEventPlan;
import com.nequi.ticketing.application.port.out.OrderRecord;
import com.nequi.ticketing.application.port.out.PaymentLease;
import com.nequi.ticketing.application.port.out.PaymentStartPlan;
import com.nequi.ticketing.application.port.out.ProvisioningFailurePlan;
import com.nequi.ticketing.application.port.out.ProvisioningSnapshot;
import com.nequi.ticketing.application.port.out.QuarantinePlan;
import com.nequi.ticketing.application.port.out.ReservationPlan;
import com.nequi.ticketing.application.port.out.ReversalCompletionPlan;
import com.nequi.ticketing.application.port.out.ReversalExhaustionPlan;
import com.nequi.ticketing.application.port.out.StalledProvisioning;
import com.nequi.ticketing.application.port.out.TransactionOutcome;
import com.nequi.ticketing.domain.audit.AuditRecord;
import com.nequi.ticketing.domain.audit.AuditRecord.Actor;
import com.nequi.ticketing.domain.audit.AuditRecord.ActorType;
import com.nequi.ticketing.domain.audit.AuditRecord.AuditCode;
import com.nequi.ticketing.domain.audit.AuditRecordBuilder;
import com.nequi.ticketing.domain.error.ValidationException;
import com.nequi.ticketing.domain.event.Event;
import com.nequi.ticketing.domain.event.Event.ProvisioningStatus;
import com.nequi.ticketing.domain.event.InventoryDefinition;
import com.nequi.ticketing.domain.event.InventoryDefinition.ComplimentaryRange;
import com.nequi.ticketing.domain.event.InventoryDefinition.Row;
import com.nequi.ticketing.domain.event.InventoryDefinition.Section;
import com.nequi.ticketing.domain.event.InventoryDefinition.ValidatedInventory;
import com.nequi.ticketing.domain.event.InventoryLimits;
import com.nequi.ticketing.domain.event.ShardingPolicy;
import com.nequi.ticketing.domain.order.ActiveOrderKey;
import com.nequi.ticketing.domain.order.Order;
import com.nequi.ticketing.domain.order.Order.FunctionalCause;
import com.nequi.ticketing.domain.order.Order.OrderStatus;
import com.nequi.ticketing.domain.order.Order.PaymentOutcome;
import com.nequi.ticketing.domain.order.PurchaseRequest;
import com.nequi.ticketing.domain.order.ReversalPlan;
import com.nequi.ticketing.domain.ticket.Ticket;
import com.nequi.ticketing.domain.ticket.Ticket.TicketState;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Port contract battery of the persistence ports (ADR-038): the same scenarios run against the in-memory
 * double of {@code application} (unit level) and against the DynamoDB adapter on DynamoDB Local
 * ({@code *IT}), so that both evaluate exactly the items and conditions of {@code ticketing.data-model.v2.md}
 * §5 and the index membership of §2.2 in the same way. Every scenario uses its own identifiers, so the
 * integration run can share one table; index queries are asserted with "contains / does not contain".
 */
abstract class PersistencePortContract {

    static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");
    static final String OWNER = "worker-1/1";
    static final Actor WORKER = new Actor(ActorType.WORKER, "worker-1");

    /** A: rows 1 and 2 with 10 seats each; B: row 1 with 5 seats, seat 5 complimentary. Capacity 25. */
    static final InventoryDefinition DEFINITION = new InventoryDefinition(
            List.of(new Section("A", List.of(new Row("1", 10), new Row("2", 10))),
                    new Section("B", List.of(new Row("1", 5)))),
            List.of(new ComplimentaryRange("B", "1", 5, 5)));

    protected PersistencePorts ports;

    /** Fresh view of the store under test for one scenario. */
    protected abstract PersistencePorts ports();

    /** Current state of a Ticket, or empty when it does not exist (inspection only). */
    protected abstract Optional<TicketState> ticketState(String eventId, String ticketId);

    /** Order owning the active Order lock of a customer and Event (inspection only). */
    protected abstract Optional<String> activeLock(String customerId, String eventId);

    /** Audit records of an Order (AP-017). */
    protected abstract List<AuditRecord> orderAudits(String orderId);

    /** Audit records of an Event (AP-017). */
    protected abstract List<AuditRecord> eventAudits(String eventId);

    /** Puts a Ticket in a state outside of any transition, to reproduce technical inconsistencies. */
    protected abstract void forceTicket(String eventId, String ticketId, TicketState state, String orderId);

    /** Index reads are eventually consistent (data model §7): assertions on them may be retried. */
    protected void eventually(Runnable assertion) {
        assertion.run();
    }

    @BeforeEach
    void openPorts() {
        ports = ports();
    }

    // ================================================================== Event catalog

    @Test
    @DisplayName("AP-001 AP-022 AP-023 FR-001 FR-017 creating an Event writes Event, idempotency and audit atomically; a repetition is cancelled on the three items")
    void createEvent() {
        Event event = newEvent(DEFINITION, NOW.plus(Duration.ofDays(10)));
        NewEventPlan plan = newEventPlan(event, NOW);

        assertThat(value(ports.events().create(plan))).isEqualTo(TransactionOutcome.applied());
        ProvisioningSnapshot snapshot = value(ports.events().findProvisioningSnapshot(event.eventId()));
        assertThat(snapshot.event()).isEqualTo(event);
        assertThat(snapshot.createdAt()).isEqualTo(NOW);
        assertThat(snapshot.provisionedBatches()).isZero();
        assertThat(snapshot.progressReference()).isEqualTo(NOW);
        assertThat(snapshot.leaseOwner()).isNull();
        assertThat(value(ports.events().findEvent(event.eventId()))).isEqualTo(event);
        IdempotencyRecord idempotency = value(ports.idempotency().findEventCreation("admin", plan.idempotency().idempotencyKey()));
        assertThat(idempotency.resourceId()).isEqualTo(event.eventId());
        assertThat(idempotency.requestHash()).isEqualTo("event-hash");
        assertThat(idempotency.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(24)));
        assertThat(eventAudits(event.eventId())).extracting(AuditRecord::code).containsExactly(AuditCode.EVENT_PROVISIONING_REQUESTED);

        TransactionOutcome repeated = value(ports.events().create(plan));
        assertThat(failedItems(repeated)).containsExactlyInAnyOrder(FailedItem.EVENT, FailedItem.IDEMPOTENCY_RECORD,
                FailedItem.AUDIT);
        assertThat(ports.events().findEvent("missing-" + UUID.randomUUID()).blockOptional()).isEmpty();
        assertThat(ports.events().findProvisioningSnapshot("missing-" + UUID.randomUUID()).blockOptional()).isEmpty();
        assertThat(ports.idempotency().findEventCreation("admin", "missing-key-0000001").blockOptional()).isEmpty();
    }

    @Test
    @DisplayName("AP-024 ADR-024 the provisioning lease is taken when free, expired or own, and progress is recorded only by its owner")
    void provisioningLeaseAndProgress() {
        Event event = createdEvent(DEFINITION);
        String id = event.eventId();
        Instant until = NOW.plusSeconds(60);

        assertThat(value(ports.events().acquireProvisioningLease(id, OWNER, until, NOW))).isTrue();
        assertThat(value(ports.events().acquireProvisioningLease(id, "worker-2/1", until, NOW.plusSeconds(60)))).isFalse();
        assertThat(value(ports.events().acquireProvisioningLease(id, OWNER, NOW.plusSeconds(90), NOW.plusSeconds(30)))).isTrue();
        assertThat(value(ports.events().recordProvisioningProgress(id, "worker-2/1", until, 1, NOW))).isFalse();
        assertThat(value(ports.events().recordProvisioningProgress(id, OWNER, NOW.plusSeconds(120), 1, NOW.plusSeconds(40))))
                .isTrue();
        ProvisioningSnapshot snapshot = value(ports.events().findProvisioningSnapshot(id));
        assertThat(snapshot.provisionedBatches()).isEqualTo(1);
        assertThat(snapshot.lastProgressAt()).isEqualTo(NOW.plusSeconds(40));
        assertThat(snapshot.leaseOwner()).isEqualTo(OWNER);
        assertThat(snapshot.leaseUntil()).isEqualTo(NOW.plusSeconds(120));

        assertThat(value(ports.events().acquireProvisioningLease(id, "worker-2/1", NOW.plusSeconds(300),
                NOW.plusSeconds(121)))).isTrue();
        assertThat(value(ports.events().acquireProvisioningLease("missing-" + UUID.randomUUID(), OWNER, until, NOW)))
                .isFalse();
        assertThat(value(ports.events().recordProvisioningProgress("missing-" + UUID.randomUUID(), OWNER, until, 1, NOW)))
                .isFalse();
    }

    @Test
    @DisplayName("AP-002 AP-025 AP-003 BR-016 AC-013 batches write the initial states, verification detects gaps and enabling needs the own lease")
    void provisionAndEnable() {
        Event event = createdEvent(DEFINITION);
        List<Ticket> expected = initialTickets(event);
        value(ports.events().acquireProvisioningLease(event.eventId(), OWNER, NOW.plusSeconds(60), NOW));

        value(ports.tickets().writeBatch(event, expected.subList(0, 20)));
        InventoryVerification partial = value(ports.tickets().verify(event, expected));
        assertThat(partial.verifiedCount()).isEqualTo(20);
        assertThat(partial.invalidTicketIds()).containsExactlyInAnyOrderElementsOf(
                expected.subList(20, 25).stream().map(Ticket::ticketId).toList());
        value(ports.tickets().writeBatch(event, expected.subList(20, 25)));
        value(ports.tickets().writeBatch(event, expected.subList(20, 25)));
        assertThat(value(ports.tickets().verify(event, expected)).complete()).isTrue();
        assertThat(ticketState(event.eventId(), "B-1-5")).contains(TicketState.COMPLIMENTARY);
        assertThat(ticketState(event.eventId(), "A-1-1")).contains(TicketState.AVAILABLE);

        Event enabled = event.enable(25);
        AuditRecord audit = eventAudit(AuditCode.EVENT_ENABLED, event.eventId(), NOW.plusSeconds(5));
        assertThat(failedItems(value(ports.events().enable(new EnablementPlan(event, enabled, "worker-2/1", audit,
                NOW.plusSeconds(5)))))).containsExactly(FailedItem.EVENT);
        assertThat(value(ports.events().enable(new EnablementPlan(event, enabled, OWNER, audit, NOW.plusSeconds(5)))))
                .isEqualTo(TransactionOutcome.applied());
        ProvisioningSnapshot snapshot = value(ports.events().findProvisioningSnapshot(event.eventId()));
        assertThat(snapshot.event().provisioningStatus()).isEqualTo(ProvisioningStatus.ENABLED);
        assertThat(snapshot.enabledAt()).isEqualTo(NOW.plusSeconds(5));
        assertThat(snapshot.leaseOwner()).isNull();
        assertThat(value(ports.events().findEvent(event.eventId()))).isEqualTo(enabled);
        assertThat(failedItems(value(ports.events().enable(new EnablementPlan(event, enabled, OWNER, audit,
                NOW.plusSeconds(5)))))).containsExactlyInAnyOrder(FailedItem.EVENT, FailedItem.AUDIT);
        assertThat(eventAudits(event.eventId())).extracting(AuditRecord::code)
                .containsExactlyInAnyOrder(AuditCode.EVENT_PROVISIONING_REQUESTED, AuditCode.EVENT_ENABLED);
        assertThat(value(ports.events().acquireProvisioningLease(event.eventId(), OWNER, NOW.plusSeconds(60), NOW)))
                .isFalse();
    }

    @Test
    @DisplayName("AP-026 AP-027 ADR-024 a failed provisioning is marked FAILED, listed for purge, purged and leaves the purge index")
    void failAndPurge() {
        Event event = createdEvent(DEFINITION);
        List<Ticket> expected = initialTickets(event);
        value(ports.events().acquireProvisioningLease(event.eventId(), OWNER, NOW.plusSeconds(60), NOW));
        value(ports.tickets().writeBatch(event, expected));
        AuditRecord audit = eventAudit(AuditCode.EVENT_PROVISIONING_FAILED, event.eventId(), NOW.plusSeconds(9));
        ProvisioningFailurePlan plan = new ProvisioningFailurePlan(event, event.fail(), audit, NOW.plusSeconds(9));

        assertThat(value(ports.events().markTicketsPurged(event.eventId(), NOW))).isFalse();
        assertThat(value(ports.events().markFailed(plan))).isEqualTo(TransactionOutcome.applied());
        ProvisioningSnapshot snapshot = value(ports.events().findProvisioningSnapshot(event.eventId()));
        assertThat(snapshot.event().provisioningStatus()).isEqualTo(ProvisioningStatus.FAILED);
        assertThat(snapshot.failedAt()).isEqualTo(NOW.plusSeconds(9));
        assertThat(snapshot.leaseOwner()).isNull();
        assertThat(failedItems(value(ports.events().markFailed(plan))))
                .containsExactlyInAnyOrder(FailedItem.EVENT, FailedItem.AUDIT);
        eventually(() -> assertThat(collect(ports.events().findFailedPendingPurge())).contains(event.eventId()));

        value(ports.tickets().purge(event, expected.stream().map(Ticket::ticketId).toList()));
        assertThat(value(ports.tickets().verify(event, expected)).verifiedCount()).isZero();
        assertThat(value(ports.events().markTicketsPurged(event.eventId(), NOW.plusSeconds(20)))).isTrue();
        assertThat(value(ports.events().findProvisioningSnapshot(event.eventId())).ticketsPurgedAt())
                .isEqualTo(NOW.plusSeconds(20));
        eventually(() -> assertThat(collect(ports.events().findFailedPendingPurge())).doesNotContain(event.eventId()));
    }

    @Test
    @DisplayName("AP-018 AP-033 ADR-024 stalled provisioning is found by last progress and its republication is conditional on the progress read")
    void stalledProvisioning() {
        Event event = createdEvent(DEFINITION);
        String id = event.eventId();

        eventually(() -> assertThat(collect(ports.events().findStalledProvisioning(NOW.plusSeconds(1))))
                .contains(new StalledProvisioning(id, NOW, 0)));
        assertThat(collect(ports.events().findStalledProvisioning(NOW)).stream().map(StalledProvisioning::eventId))
                .doesNotContain(id);
        assertThat(value(ports.events().registerRepublication(id, NOW.minusSeconds(1), NOW.plusSeconds(200)))).isFalse();
        assertThat(value(ports.events().registerRepublication(id, NOW, NOW.plusSeconds(200)))).isTrue();
        ProvisioningSnapshot snapshot = value(ports.events().findProvisioningSnapshot(id));
        assertThat(snapshot.republishCount()).isEqualTo(1);
        assertThat(snapshot.progressReference()).isEqualTo(NOW.plusSeconds(200));
        eventually(() -> assertThat(collect(ports.events().findStalledProvisioning(NOW.plusSeconds(1)))
                .stream().map(StalledProvisioning::eventId)).doesNotContain(id));
        eventually(() -> assertThat(collect(ports.events().findStalledProvisioning(NOW.plusSeconds(201))))
                .contains(new StalledProvisioning(id, NOW.plusSeconds(200), 1)));
    }

    @Test
    @DisplayName("AP-004 BR-022 FR-002 the listing returns ENABLED future Events in start order with an exact cursor; invalid cursors are rejected")
    void listEnabledUpcoming() {
        Instant base = Instant.parse("2090-01-01T00:00:00Z").plus(ThreadLocalRandom.current().nextLong(1, 3_000_000),
                ChronoUnit.MINUTES);
        Event first = enabledEvent(DEFINITION, base.plusSeconds(10));
        Event second = enabledEvent(DEFINITION, base.plusSeconds(20));
        Event third = enabledEvent(DEFINITION, base.plusSeconds(30));
        createdEvent(DEFINITION, base.plusSeconds(15));

        eventually(() -> {
            EnabledEventPage page = value(ports.events().listEnabledUpcoming(base, 2, null));
            assertThat(page.events()).extracting(Event::eventId).containsExactly(first.eventId(), second.eventId());
            assertThat(page.events().getFirst().name()).isEqualTo(first.name());
            assertThat(page.events().getFirst().availabilityShards()).isEqualTo(first.availabilityShards());
            assertThat(page.nextCursor()).isNotNull();
            EnabledEventPage next = value(ports.events().listEnabledUpcoming(base, 1, page.nextCursor()));
            assertThat(next.events()).extracting(Event::eventId).containsExactly(third.eventId());
        });
        EnabledEventPage afterSecond = value(ports.events().listEnabledUpcoming(base.plusSeconds(20), 5, null));
        assertThat(afterSecond.events()).extracting(Event::eventId).startsWith(third.eventId())
                .doesNotContain(first.eventId(), second.eventId());
        assertThatThrownBy(() -> value(ports.events().listEnabledUpcoming(base, 2, "not-a-cursor!")))
                .isInstanceOf(ValidationException.class);
    }

    // ================================================================== Ticket inventory (availability)

    @Test
    @DisplayName("AP-005 AP-020 AP-021 AC-010 ADR-040 count, probe and paginated pages list only AVAILABLE Tickets once; section filter and cursors are validated")
    void availability() {
        Event event = enabledEvent(DEFINITION, NOW.plus(Duration.ofDays(10)));
        reserve(event, newOrder(event, customer(), "A-1-1", "A-1-2"));

        eventually(() -> {
            assertThat(value(ports.tickets().countAvailable(event))).isEqualTo(22);
            assertThat(value(ports.tickets().hasAvailable(event))).isTrue();
            List<String> seen = pageThrough(event, null, 7);
            assertThat(seen).hasSize(22).doesNotHaveDuplicates().doesNotContain("A-1-1", "A-1-2", "B-1-5");
            assertThat(pageThrough(event, "B", 3)).containsExactlyInAnyOrder("B-1-1", "B-1-2", "B-1-3", "B-1-4");
        });
        AvailableTicketPage exact = value(ports.tickets().findAvailablePage(event, "B", 4, null));
        assertThat(exact.tickets()).extracting(AvailableTicket::section).containsOnly("B");
        assertThat(exact.nextCursor()).as("no cursor when no more Tickets exist").isNull();
        AvailableTicket sample = exact.tickets().getFirst();
        assertThat(sample.ticketId()).isEqualTo(sample.section() + "-" + sample.row() + "-" + sample.seat());

        String cursor = value(ports.tickets().findAvailablePage(event, null, 2, null)).nextCursor();
        Event other = enabledEvent(DEFINITION, NOW.plus(Duration.ofDays(11)));
        assertThatThrownBy(() -> value(ports.tickets().findAvailablePage(event, "A", 2, cursor)))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> value(ports.tickets().findAvailablePage(other, null, 2, cursor)))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> value(ports.tickets().findAvailablePage(event, null, 2, "%%%")))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("AP-005 AP-021 BR-012 a sold-out Event has no AVAILABLE Ticket and a zero count")
    void soldOut() {
        InventoryDefinition tiny = new InventoryDefinition(List.of(new Section("A", List.of(new Row("1", 2)))), List.of());
        Event event = enabledEvent(tiny, NOW.plus(Duration.ofDays(10)));
        reserve(event, newOrder(event, customer(), "A-1-1", "A-1-2"));

        eventually(() -> {
            assertThat(value(ports.tickets().countAvailable(event))).isZero();
            assertThat(value(ports.tickets().hasAvailable(event))).isFalse();
            assertThat(value(ports.tickets().findAvailablePage(event, null, 10, null)).tickets()).isEmpty();
        });
    }

    @Test
    @DisplayName("AP-020 AP-021 ADR-022 ADR-040 an Event with several availability shards is counted and paginated across all of them")
    void severalShards() {
        InventoryDefinition large = new InventoryDefinition(List.of(
                new Section("A", List.of(new Row("1", 900), new Row("2", 900), new Row("3", 900))),
                new Section("B", List.of(new Row("1", 900), new Row("2", 900)))), List.of());
        Event event = enabledEvent(large, NOW.plus(Duration.ofDays(10)));
        assertThat(event.availabilityShards()).isEqualTo(3);

        eventually(() -> {
            assertThat(value(ports.tickets().countAvailable(event))).isEqualTo(4_500);
            List<String> seen = pageThrough(event, null, 100);
            assertThat(seen).hasSize(4_500).doesNotHaveDuplicates();
            assertThat(pageThrough(event, "B", 100)).hasSize(1_800).allMatch(id -> id.startsWith("B-"));
        });
    }

    // ================================================================== Order lifecycle (api role)

    @Test
    @DisplayName("AP-008 AP-009 AP-010 AC-004 FR-004 ST-001 ST-006 a reservation writes Tickets, Order, idempotency, audit and lock atomically")
    void reserveApplies() {
        Event event = enabledEvent(DEFINITION, NOW.plus(Duration.ofDays(10)));
        String customer = customer();
        Order order = newOrder(event, customer, "A-1-1", "A-1-2", "A-2-10");
        ReservationPlan plan = reservationPlan(order);

        assertThat(value(ports.lifecycle().reserve(plan))).isEqualTo(TransactionOutcome.applied());
        assertThat(order.ticketIds()).allSatisfy(id -> assertThat(ticketState(event.eventId(), id)).contains(TicketState.RESERVED));
        assertThat(activeLock(customer, event.eventId())).contains(order.orderId());
        OrderRecord record = value(ports.orders().findById(order.orderId()));
        assertThat(record).isEqualTo(new OrderRecord(order, NOW, NOW, null, null));
        IdempotencyRecord idempotency = value(ports.idempotency().findPurchase(customer, plan.idempotency().idempotencyKey()));
        assertThat(idempotency.resourceId()).isEqualTo(order.orderId());
        assertThat(idempotency.requestHash()).isEqualTo(plan.idempotency().requestHash());
        assertThat(orderAudits(order.orderId())).containsExactly(plan.audit());
        assertThat(ports.orders().findById("missing-" + UUID.randomUUID()).blockOptional()).isEmpty();
        assertThat(ports.idempotency().findPurchase(customer, "missing-key-0000001").blockOptional()).isEmpty();
    }

    @Test
    @DisplayName("AP-008 AC-014 AC-016 AC-036 AC-043 ADR-023 a cancelled reservation reports every failed item with its Ticket and persists nothing")
    void reserveCancellationReasons() {
        Event event = enabledEvent(DEFINITION, NOW.plus(Duration.ofDays(10)));
        String customer = customer();
        Order first = newOrder(event, customer, "A-1-1");
        reserve(event, first);

        Order second = newOrder(event, customer, "A-1-1", "A-1-3", "Z-9-9");
        ReservationPlan plan = reservationPlan(second);
        TransactionOutcome.Cancelled cancelled = (TransactionOutcome.Cancelled) value(ports.lifecycle().reserve(plan));
        assertThat(cancelled.failures()).containsExactlyInAnyOrder(
                ItemFailure.ticket(FailedItem.TICKET_STATE, "A-1-1"),
                ItemFailure.ticket(FailedItem.TICKET_MISSING, "Z-9-9"),
                ItemFailure.of(FailedItem.ACTIVE_ORDER_LOCK));
        assertThat(ticketState(event.eventId(), "A-1-3")).contains(TicketState.AVAILABLE);
        assertThat(ports.orders().findById(second.orderId()).blockOptional()).isEmpty();
        assertThat(ports.idempotency().findPurchase(customer, plan.idempotency().idempotencyKey()).blockOptional()).isEmpty();
        assertThat(activeLock(customer, event.eventId())).contains(first.orderId());
        assertThat(orderAudits(second.orderId())).isEmpty();

        Order sameKey = newOrder(event, customer(), "A-1-4");
        IdempotencyRecord reused = new IdempotencyRecord(customer, reservationPlan(first).idempotency().idempotencyKey(),
                sameKey.orderId(), "other", NOW, NOW.plus(Duration.ofHours(24)));
        Order reusedOrder = new Order(sameKey.orderId(), customer, sameKey.eventId(), sameKey.ticketIds(),
                OrderStatus.CREATED, null, sameKey.reservation(), null, null, null, null);
        TransactionOutcome reusedOutcome = value(ports.lifecycle().reserve(new ReservationPlan(reusedOrder, reused,
                audit(AuditCode.RESERVATION_CREATED, reusedOrder, NOW), ActiveOrderKey.of(reusedOrder))));
        assertThat(failedItems(reusedOutcome)).containsExactlyInAnyOrder(FailedItem.IDEMPOTENCY_RECORD,
                FailedItem.ACTIVE_ORDER_LOCK);
        assertThat(ticketState(event.eventId(), "A-1-4")).contains(TicketState.AVAILABLE);
    }

    @Test
    @DisplayName("AP-008 AC-012 AC-013 BR-006 BR-007 no reservation accepts a SOLD or COMPLIMENTARY Ticket as origin")
    void finalStatesAreNeverReserved() {
        Event event = enabledEvent(DEFINITION, NOW.plus(Duration.ofDays(10)));
        Order sold = confirmedOrder(event, "A-2-1");

        Order attempt = newOrder(event, customer(), "A-2-1", "B-1-5");
        assertThat(((TransactionOutcome.Cancelled) value(ports.lifecycle().reserve(reservationPlan(attempt)))).failures())
                .containsExactlyInAnyOrder(ItemFailure.ticket(FailedItem.TICKET_STATE, "A-2-1"),
                        ItemFailure.ticket(FailedItem.TICKET_STATE, "B-1-5"));
        assertThat(ticketState(event.eventId(), "A-2-1")).contains(TicketState.SOLD);
        assertThat(ticketState(event.eventId(), "B-1-5")).contains(TicketState.COMPLIMENTARY);
        assertThat(value(ports.orders().findById(sold.orderId())).order().status()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    @DisplayName("AP-011 AP-028 ADR-026 marking enqueued applies once, keeps the Order CREATED and removes it from the pending-enqueue index")
    void markEnqueued() {
        Event event = enabledEvent(DEFINITION, NOW.plus(Duration.ofDays(10)));
        Order order = reserve(event, newOrder(event, customer(), "A-1-5"));
        int shard = ShardingPolicy.shard(order.orderId(), ShardingPolicy.PENDING_ENQUEUE_SHARDS);

        eventually(() -> assertThat(collect(ports.orders().findPendingEnqueue(shard, NOW.plusSeconds(31))))
                .contains(order.orderId()));
        assertThat(collect(ports.orders().findPendingEnqueue(shard, NOW))).doesNotContain(order.orderId());
        assertThat(value(ports.lifecycle().markEnqueued(order.orderId(), NOW.plusSeconds(1)))).isTrue();
        assertThat(value(ports.lifecycle().markEnqueued(order.orderId(), NOW.plusSeconds(2)))).isFalse();
        assertThat(value(ports.lifecycle().markEnqueued("missing-" + UUID.randomUUID(), NOW))).isFalse();
        OrderRecord record = value(ports.orders().findById(order.orderId()));
        assertThat(record.enqueuedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(record.order().status()).isEqualTo(OrderStatus.CREATED);
        eventually(() -> assertThat(collect(ports.orders().findPendingEnqueue(shard, NOW.plusSeconds(31))))
                .doesNotContain(order.orderId()));
    }

    @Test
    @DisplayName("AP-015 AC-022 AC-045 ST-009 Fail (enqueue) releases the Tickets, removes the lock and is applied once")
    void failEnqueue() {
        Event event = enabledEvent(DEFINITION, NOW.plus(Duration.ofDays(10)));
        String customer = customer();
        Order order = reserve(event, newOrder(event, customer, "A-1-6", "A-1-7"));
        Order failed = order.failEnqueue();
        EnqueueFailurePlan plan = new EnqueueFailurePlan(order, failed,
                audit(AuditCode.ENQUEUE_FAILED, failed, NOW.plusSeconds(3)), NOW.plusSeconds(3));

        assertThat(value(ports.lifecycle().failEnqueue(plan))).isEqualTo(TransactionOutcome.applied());
        OrderRecord record = value(ports.orders().findById(order.orderId()));
        assertThat(record.order()).isEqualTo(failed);
        assertThat(record.order().failureCause()).isEqualTo(FunctionalCause.PROCESSING_UNAVAILABLE);
        assertThat(record.updatedAt()).isEqualTo(NOW.plusSeconds(3));
        assertThat(order.ticketIds()).allSatisfy(id -> assertThat(ticketState(event.eventId(), id)).contains(TicketState.AVAILABLE));
        assertThat(activeLock(customer, event.eventId())).isEmpty();
        assertThat(failedItems(value(ports.lifecycle().failEnqueue(plan))))
                .contains(FailedItem.ORDER, FailedItem.TICKET_STATE, FailedItem.AUDIT);
        assertThat(orderAudits(order.orderId())).extracting(AuditRecord::code)
                .containsExactlyInAnyOrder(AuditCode.RESERVATION_CREATED, AuditCode.ENQUEUE_FAILED);
        Order again = reserve(event, newOrder(event, customer, "A-1-6"));
        assertThat(activeLock(customer, event.eventId())).contains(again.orderId());
    }

    @Test
    @DisplayName("AP-031 ADR-025 quarantine keeps the Tickets and the lock, leaves the expiration and pending indexes and is applied once")
    void quarantine() {
        Event event = enabledEvent(DEFINITION, NOW.plus(Duration.ofDays(10)));
        String customer = customer();
        Order order = reserve(event, newOrder(event, customer, "A-1-8"));
        Order quarantined = order.quarantine(NOW.plusSeconds(4), "TICKET_CONDITION_FAILED_ON_EXPIRATION");
        QuarantinePlan plan = new QuarantinePlan(order, quarantined, audit(AuditCode.ORDER_QUARANTINED, order, NOW.plusSeconds(4)));
        int reservationShard = ShardingPolicy.shard(order.orderId(), ShardingPolicy.RESERVATION_SHARDS);
        int pendingShard = ShardingPolicy.shard(order.orderId(), ShardingPolicy.PENDING_ENQUEUE_SHARDS);
        Instant afterExpiry = order.reservation().expiresAt().plusSeconds(1);

        eventually(() -> assertThat(collect(ports.orders().findDueReservations(reservationShard, afterExpiry)))
                .contains(order.orderId()));
        assertThat(value(ports.lifecycle().quarantine(plan))).isEqualTo(TransactionOutcome.applied());
        OrderRecord record = value(ports.orders().findById(order.orderId()));
        assertThat(record.order()).isEqualTo(quarantined);
        assertThat(ticketState(event.eventId(), "A-1-8")).contains(TicketState.RESERVED);
        assertThat(activeLock(customer, event.eventId())).contains(order.orderId());
        eventually(() -> {
            assertThat(collect(ports.orders().findDueReservations(reservationShard, afterExpiry))).doesNotContain(order.orderId());
            assertThat(collect(ports.orders().findPendingEnqueue(pendingShard, afterExpiry))).doesNotContain(order.orderId());
        });
        assertThat(failedItems(value(ports.lifecycle().quarantine(plan)))).contains(FailedItem.ORDER);
    }

    // ================================================================== Order lifecycle (worker role)

    @Test
    @DisplayName("AP-012 AC-005 AC-049 ST-003 starting the payment needs CREATED, no attempt, no quarantine and expiresAt beyond the cutoff")
    void startPayment() {
        Event event = enabledEvent(DEFINITION, NOW.plus(Duration.ofDays(10)));
        Order order = reserve(event, newOrder(event, customer(), "A-2-2", "A-2-3"));
        Instant late = order.reservation().expiresAt().minusSeconds(15);
        Order tooLate = order.startPayment(late.minusSeconds(1));
        assertThat(failedItems(value(ports.lifecycle().startPayment(new PaymentStartPlan(order, tooLate, lease(late), audit(
                AuditCode.PAYMENT_STARTED, tooLate, late), late, late.plusSeconds(15)))))).containsExactly(FailedItem.ORDER);

        Order started = startPayment(order, NOW.plusSeconds(5));
        OrderRecord record = value(ports.orders().findById(order.orderId()));
        assertThat(record.order()).isEqualTo(started);
        assertThat(record.paymentLease()).isEqualTo(lease(NOW.plusSeconds(5)));
        assertThat(record.updatedAt()).isEqualTo(NOW.plusSeconds(5));
        assertThat(order.ticketIds()).allSatisfy(id -> assertThat(ticketState(event.eventId(), id))
                .contains(TicketState.PENDING_CONFIRMATION));
        Order again = order.startPayment(NOW.plusSeconds(6));
        assertThat(failedItems(value(ports.lifecycle().startPayment(new PaymentStartPlan(order, again, lease(NOW.plusSeconds(6)),
                audit(AuditCode.PAYMENT_STARTED, again, NOW.plusSeconds(6)), NOW.plusSeconds(6), NOW.plusSeconds(21))))))
                .containsExactlyInAnyOrder(FailedItem.ORDER, FailedItem.TICKET_STATE);
    }

    @Test
    @DisplayName("AP-013 AC-024 BR-020 the payment lease is claimed only when expired, for the same attempt")
    void claimPaymentLease() {
        Event event = enabledEvent(DEFINITION, NOW.plus(Duration.ofDays(10)));
        Order started = startPayment(reserve(event, newOrder(event, customer(), "A-2-4")), NOW.plusSeconds(5));
        String attempt = started.paymentAttempt().paymentAttemptId();
        PaymentLease mine = new PaymentLease("worker-2/7", NOW.plusSeconds(120));

        assertThat(value(ports.lifecycle().claimPaymentLease(started.orderId(), attempt, mine, NOW.plusSeconds(50)))).isFalse();
        assertThat(value(ports.lifecycle().claimPaymentLease(started.orderId(), "other-1", mine, NOW.plusSeconds(51)))).isFalse();
        assertThat(value(ports.lifecycle().claimPaymentLease(started.orderId(), attempt, mine, NOW.plusSeconds(51)))).isTrue();
        assertThat(value(ports.orders().findById(started.orderId())).paymentLease()).isEqualTo(mine);
    }

    @Test
    @DisplayName("AP-014 AC-019 AC-051 ST-004 ST-007 confirming sells every Ticket, removes the lock and needs expiresAt > now")
    void confirm() {
        Event event = enabledEvent(DEFINITION, NOW.plus(Duration.ofDays(10)));
        String customer = customer();
        Order started = startPayment(reserve(event, newOrder(event, customer, "A-2-5", "A-2-6")), NOW.plusSeconds(5));
        Order approved = started.recordPaymentOutcome(PaymentOutcome.APPROVED);
        Instant expiry = started.reservation().expiresAt();
        Order confirmedLate = approved.confirm(expiry.minusMillis(1));
        assertThat(failedItems(value(ports.lifecycle().confirm(new ConfirmationPlan(started, confirmedLate, "ref",
                audit(AuditCode.PAYMENT_APPROVED, confirmedLate, expiry), expiry))))).containsExactly(FailedItem.ORDER);

        Order confirmed = approved.confirm(NOW.plusSeconds(8));
        ConfirmationPlan plan = new ConfirmationPlan(started, confirmed, "provider-ref",
                audit(AuditCode.PAYMENT_APPROVED, confirmed, NOW.plusSeconds(8)), NOW.plusSeconds(8));
        assertThat(value(ports.lifecycle().confirm(plan))).isEqualTo(TransactionOutcome.applied());
        OrderRecord record = value(ports.orders().findById(started.orderId()));
        assertThat(record.order()).isEqualTo(confirmed);
        assertThat(record.paymentLease()).isNull();
        assertThat(started.ticketIds()).allSatisfy(id -> assertThat(ticketState(event.eventId(), id)).contains(TicketState.SOLD));
        assertThat(activeLock(customer, event.eventId())).isEmpty();
        int shard = ShardingPolicy.shard(started.orderId(), ShardingPolicy.RESERVATION_SHARDS);
        eventually(() -> assertThat(collect(ports.orders().findDueReservations(shard, expiry.plusSeconds(1))))
                .doesNotContain(started.orderId()));
        assertThat(failedItems(value(ports.lifecycle().confirm(plan)))).contains(FailedItem.ORDER, FailedItem.TICKET_STATE);
    }

    @Test
    @DisplayName("AP-015 AC-020 ST-008 rejecting releases the Tickets without reversal and needs the same PaymentAttempt")
    void reject() {
        Event event = enabledEvent(DEFINITION, NOW.plus(Duration.ofDays(10)));
        String customer = customer();
        Order started = startPayment(reserve(event, newOrder(event, customer, "A-2-7")), NOW.plusSeconds(5));
        Order rejected = started.recordPaymentOutcome(PaymentOutcome.DECLINED).reject();
        ClosurePlan plan = new ClosurePlan(ClosurePlan.Kind.REJECT, started, rejected, null,
                audit(AuditCode.PAYMENT_DECLINED, rejected, NOW.plusSeconds(7)), NOW.plusSeconds(7));

        assertThat(value(ports.lifecycle().close(plan))).isEqualTo(TransactionOutcome.applied());
        OrderRecord record = value(ports.orders().findById(started.orderId()));
        assertThat(record.order()).isEqualTo(rejected);
        assertThat(record.order().reversalPlan()).isNull();
        assertThat(ticketState(event.eventId(), "A-2-7")).contains(TicketState.AVAILABLE);
        assertThat(activeLock(customer, event.eventId())).isEmpty();
        assertThat(failedItems(value(ports.lifecycle().close(plan)))).contains(FailedItem.ORDER, FailedItem.TICKET_STATE);
    }

    @Test
    @DisplayName("AP-015 AP-029 AC-021 FG-003 a processing failure with unknown outcome marks the reversal and enters the reversal index")
    void failProcessingWithReversal() {
        Event event = enabledEvent(DEFINITION, NOW.plus(Duration.ofDays(10)));
        Order started = startPayment(reserve(event, newOrder(event, customer(), "A-2-8")), NOW.plusSeconds(5));
        Order failed = started.failProcessing(NOW.plusSeconds(9));
        AuditRecord audit = audit(AuditCode.PROCESSING_FAILED, failed, NOW.plusSeconds(9), AuditCode.PAYMENT_REVERSAL_REQUESTED);

        assertThat(value(ports.lifecycle().close(new ClosurePlan(ClosurePlan.Kind.FAIL_PROCESSING, started, failed, null,
                audit, NOW.plusSeconds(9))))).isEqualTo(TransactionOutcome.applied());
        OrderRecord record = value(ports.orders().findById(started.orderId()));
        assertThat(record.order()).isEqualTo(failed);
        assertThat(record.order().reversalPending()).isTrue();
        int shard = ShardingPolicy.shard(started.orderId(), ShardingPolicy.REVERSAL_SHARDS);
        eventually(() -> assertThat(collect(ports.orders().findDueReversals(shard, NOW.plusSeconds(9))))
                .contains(started.orderId()));
        assertThat(collect(ports.orders().findDueReversals(shard, NOW.plusSeconds(8)))).doesNotContain(started.orderId());
        assertThat(orderAudits(started.orderId())).contains(audit);
    }

    @Test
    @DisplayName("AP-015 ST-009 a processing failure without PaymentAttempt needs the attempt to be still absent")
    void failProcessingWithoutAttempt() {
        Event event = enabledEvent(DEFINITION, NOW.plus(Duration.ofDays(10)));
        Order order = reserve(event, newOrder(event, customer(), "A-2-9"));
        Order failed = order.failProcessing(NOW.plusSeconds(9));
        ClosurePlan plan = new ClosurePlan(ClosurePlan.Kind.FAIL_PROCESSING, order, failed, null,
                audit(AuditCode.PROCESSING_FAILED, failed, NOW.plusSeconds(9)), NOW.plusSeconds(9));
        startPayment(order, NOW.plusSeconds(5));

        assertThat(failedItems(value(ports.lifecycle().close(plan)))).contains(FailedItem.ORDER);
        assertThat(value(ports.orders().findById(order.orderId())).order().status()).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    @DisplayName("AP-015 AP-016 AC-008 AC-009 ST-002 ST-010 expiring needs expiresAt <= now and leaves the expiration index")
    void expire() {
        Event event = enabledEvent(DEFINITION, NOW.plus(Duration.ofDays(10)));
        String customer = customer();
        Order order = reserve(event, newOrder(event, customer, "B-1-1", "B-1-2"));
        Instant expiry = order.reservation().expiresAt();
        int shard = ShardingPolicy.shard(order.orderId(), ShardingPolicy.RESERVATION_SHARDS);
        Order expired = order.expire(expiry);
        ClosurePlan early = new ClosurePlan(ClosurePlan.Kind.EXPIRE, order, expired, null,
                audit(AuditCode.RESERVATION_EXPIRED, expired, expiry.minusMillis(1)), expiry.minusMillis(1));

        eventually(() -> assertThat(collect(ports.orders().findDueReservations(shard, expiry))).contains(order.orderId()));
        assertThat(collect(ports.orders().findDueReservations(shard, expiry.minusMillis(1)))).doesNotContain(order.orderId());
        assertThat(failedItems(value(ports.lifecycle().close(early)))).containsExactly(FailedItem.ORDER);
        assertThat(ticketState(event.eventId(), "B-1-1")).contains(TicketState.RESERVED);

        ClosurePlan plan = new ClosurePlan(ClosurePlan.Kind.EXPIRE, order, expired, null,
                audit(AuditCode.RESERVATION_EXPIRED, expired, expiry), expiry);
        assertThat(value(ports.lifecycle().close(plan))).isEqualTo(TransactionOutcome.applied());
        assertThat(value(ports.orders().findById(order.orderId())).order()).isEqualTo(expired);
        assertThat(order.ticketIds()).allSatisfy(id -> assertThat(ticketState(event.eventId(), id)).contains(TicketState.AVAILABLE));
        assertThat(activeLock(customer, event.eventId())).isEmpty();
        eventually(() -> assertThat(collect(ports.orders().findDueReservations(shard, expiry))).doesNotContain(order.orderId()));
    }

    @Test
    @DisplayName("AP-015 AP-031 ADR-025 a terminal transition cancelled by a Ticket condition with the Order CREATED leads to quarantine")
    void ticketConditionLeadsToQuarantine() {
        Event event = enabledEvent(DEFINITION, NOW.plus(Duration.ofDays(10)));
        String customer = customer();
        Order order = reserve(event, newOrder(event, customer, "B-1-3", "B-1-4"));
        forceTicket(event.eventId(), "B-1-4", TicketState.SOLD, "another-order");
        Instant expiry = order.reservation().expiresAt();
        Order expired = order.expire(expiry);

        TransactionOutcome outcome = value(ports.lifecycle().close(new ClosurePlan(ClosurePlan.Kind.EXPIRE, order, expired,
                null, audit(AuditCode.RESERVATION_EXPIRED, expired, expiry), expiry)));
        assertThat(((TransactionOutcome.Cancelled) outcome).failures())
                .containsExactly(ItemFailure.ticket(FailedItem.TICKET_STATE, "B-1-4"));
        assertThat(ticketState(event.eventId(), "B-1-3")).contains(TicketState.RESERVED);

        Order quarantined = order.quarantine(expiry, "TICKET_CONDITION_FAILED_ON_EXPIRATION");
        assertThat(value(ports.lifecycle().quarantine(new QuarantinePlan(order, quarantined,
                audit(AuditCode.ORDER_QUARANTINED, order, expiry))))).isEqualTo(TransactionOutcome.applied());
        assertThat(value(ports.orders().findById(order.orderId())).order().quarantinedAt()).isEqualTo(expiry);
        assertThat(activeLock(customer, event.eventId())).contains(order.orderId());
        assertThat(ticketState(event.eventId(), "B-1-3")).contains(TicketState.RESERVED);
        assertThat(ticketState(event.eventId(), "B-1-4")).contains(TicketState.SOLD);
    }

    @Test
    @DisplayName("AP-032 AC-025 AC-034 ADR-008 a late approval marks the reversal once; with a reversal already marked only the audit is written")
    void lateApproval() {
        Event event = enabledEvent(DEFINITION, NOW.plus(Duration.ofDays(10)));
        Order started = startPayment(reserve(event, newOrder(event, customer(), "A-1-9")), NOW.plusSeconds(5));
        Order rejected = started.recordPaymentOutcome(PaymentOutcome.DECLINED).reject();
        value(ports.lifecycle().close(new ClosurePlan(ClosurePlan.Kind.REJECT, started, rejected, null,
                audit(AuditCode.PAYMENT_DECLINED, rejected, NOW.plusSeconds(7)), NOW.plusSeconds(7))));

        Order marked = rejected.recordLateApproval(NOW.plusSeconds(20));
        AuditRecord firstAudit = audit(AuditCode.LATE_APPROVAL_NOT_APPLIED, rejected, NOW.plusSeconds(20));
        assertThat(value(ports.lifecycle().recordLateApproval(new LateApprovalPlan(rejected, marked, firstAudit))))
                .isEqualTo(TransactionOutcome.applied());
        assertThat(value(ports.orders().findById(started.orderId())).order()).isEqualTo(marked);

        AuditRecord secondAudit = audit(AuditCode.LATE_APPROVAL_NOT_APPLIED, marked, NOW.plusSeconds(30));
        assertThat(value(ports.lifecycle().recordLateApproval(new LateApprovalPlan(marked, marked.recordLateApproval(
                NOW.plusSeconds(30)), secondAudit)))).isEqualTo(TransactionOutcome.applied());
        assertThat(value(ports.orders().findById(started.orderId())).order()).isEqualTo(marked);
        assertThat(orderAudits(started.orderId())).contains(firstAudit, secondAudit);

        Order open = startPayment(reserve(event, newOrder(event, customer(), "A-1-10")), NOW.plusSeconds(5));
        Order fakeClosed = open.failProcessing(NOW.plusSeconds(6));
        assertThat(failedItems(value(ports.lifecycle().recordLateApproval(new LateApprovalPlan(fakeClosed,
                fakeClosed, audit(AuditCode.LATE_APPROVAL_NOT_APPLIED, open, NOW.plusSeconds(40)))))))
                .contains(FailedItem.ORDER);
    }

    @Test
    @DisplayName("AP-030 AP-029 FG-003 ADR-025 a reversal is rescheduled with the attempts read, completed once and exhausted to manual review")
    void reversals() {
        Event event = enabledEvent(DEFINITION, NOW.plus(Duration.ofDays(10)));
        Order failed = failedWithReversal(event, "A-2-1");
        int shard = ShardingPolicy.shard(failed.orderId(), ShardingPolicy.REVERSAL_SHARDS);

        Order rescheduled = failed.rescheduleReversal(NOW.plusSeconds(10));
        assertThat(value(ports.lifecycle().rescheduleReversal(failed.orderId(), 1, rescheduled.reversalPlan()))).isFalse();
        assertThat(value(ports.lifecycle().rescheduleReversal(failed.orderId(), 0, rescheduled.reversalPlan()))).isTrue();
        assertThat(value(ports.orders().findById(failed.orderId())).order().reversalPlan()).isEqualTo(rescheduled.reversalPlan());
        Instant next = rescheduled.reversalPlan().nextAttemptAt();
        eventually(() -> {
            assertThat(collect(ports.orders().findDueReversals(shard, next.minusMillis(1)))).doesNotContain(failed.orderId());
            assertThat(collect(ports.orders().findDueReversals(shard, next))).contains(failed.orderId());
        });

        Order completed = rescheduled.completeReversal(NOW.plusSeconds(25));
        ReversalCompletionPlan completion = new ReversalCompletionPlan(rescheduled, completed,
                audit(AuditCode.PAYMENT_REVERSAL_CONFIRMED, completed, NOW.plusSeconds(25)));
        assertThat(value(ports.lifecycle().completeReversal(completion))).isEqualTo(TransactionOutcome.applied());
        assertThat(value(ports.orders().findById(failed.orderId())).order()).isEqualTo(completed);
        eventually(() -> assertThat(collect(ports.orders().findDueReversals(shard, NOW.plus(Duration.ofDays(1)))))
                .doesNotContain(failed.orderId()));
        assertThat(failedItems(value(ports.lifecycle().completeReversal(completion)))).contains(FailedItem.ORDER);

        Order other = failedWithReversal(event, "A-2-2");
        Order exhausted = other;
        for (int attempt = 0; attempt < ReversalPlan.MAXIMUM_ATTEMPTS; attempt++) {
            exhausted = exhausted.rescheduleReversal(NOW.plusSeconds(60 + attempt));
        }
        ReversalExhaustionPlan exhaustion = new ReversalExhaustionPlan(other, exhausted,
                audit(AuditCode.PAYMENT_REVERSAL_EXHAUSTED, exhausted, NOW.plusSeconds(90)));
        assertThat(value(ports.lifecycle().exhaustReversal(exhaustion))).isEqualTo(TransactionOutcome.applied());
        Order stored = value(ports.orders().findById(other.orderId())).order();
        assertThat(stored.reversalPlan().exhausted()).isTrue();
        assertThat(stored.reversalPending()).isTrue();
        int otherShard = ShardingPolicy.shard(other.orderId(), ShardingPolicy.REVERSAL_SHARDS);
        eventually(() -> assertThat(collect(ports.orders().findDueReversals(otherShard, NOW.plus(Duration.ofDays(1)))))
                .doesNotContain(other.orderId()));
        assertThat(value(ports.lifecycle().rescheduleReversal("missing-" + UUID.randomUUID(), 0, rescheduled.reversalPlan())))
                .isFalse();
    }

    @Test
    @DisplayName("AC-015 FR-014 ADR-031 IV-013 every transition leaves its audit record in the same write, with the included causes")
    void auditTrail() {
        Event event = enabledEvent(DEFINITION, NOW.plus(Duration.ofDays(10)));
        Order started = startPayment(reserve(event, newOrder(event, customer(), "A-1-3")), NOW.plusSeconds(5));
        Instant expiry = started.reservation().expiresAt();
        Order expired = started.expire(expiry);
        AuditRecord expiredAudit = audit(AuditCode.RESERVATION_EXPIRED, expired, expiry,
                AuditCode.PAYMENT_REVERSAL_REQUESTED, AuditCode.LATE_APPROVAL_NOT_APPLIED);
        value(ports.lifecycle().close(new ClosurePlan(ClosurePlan.Kind.EXPIRE, started, expired, null, expiredAudit, expiry)));

        List<AuditRecord> audits = orderAudits(started.orderId());
        assertThat(audits).extracting(AuditRecord::code).containsExactlyInAnyOrder(AuditCode.RESERVATION_CREATED,
                AuditCode.PAYMENT_STARTED, AuditCode.RESERVATION_EXPIRED);
        AuditRecord stored = audits.stream().filter(record -> record.code() == AuditCode.RESERVATION_EXPIRED).findFirst()
                .orElseThrow();
        assertThat(stored).isEqualTo(expiredAudit);
        assertThat(stored.records(AuditCode.PAYMENT_REVERSAL_REQUESTED)).isTrue();
        assertThat(stored.records(AuditCode.LATE_APPROVAL_NOT_APPLIED)).isTrue();
        assertThat(eventAudits(event.eventId())).extracting(AuditRecord::code)
                .containsExactlyInAnyOrder(AuditCode.EVENT_PROVISIONING_REQUESTED, AuditCode.EVENT_ENABLED);
    }

    // ================================================================== fixtures

    protected static <T> T value(Mono<T> mono) {
        return mono.block(Duration.ofSeconds(30));
    }

    protected static <T> List<T> collect(Flux<T> flux) {
        return flux.collectList().block(Duration.ofSeconds(30));
    }

    protected static List<FailedItem> failedItems(TransactionOutcome outcome) {
        assertThat(outcome).isInstanceOf(TransactionOutcome.Cancelled.class);
        return ((TransactionOutcome.Cancelled) outcome).failures().stream().map(ItemFailure::item).distinct().toList();
    }

    protected static String customer() {
        return "customer-" + UUID.randomUUID();
    }

    protected static Event newEvent(InventoryDefinition definition, Instant startsAt) {
        return Event.create(UUID.randomUUID().toString(), "Concert", "Arena", startsAt, capacity(definition), definition,
                NOW, InventoryLimits.DEPLOYED);
    }

    protected static NewEventPlan newEventPlan(Event event, Instant createdAt) {
        ValidatedInventory inventory = event.inventoryDefinition().validate(event.capacity(), InventoryLimits.DEPLOYED);
        int complimentary = (int) inventory.complimentaryCount();
        IdempotencyRecord idempotency = new IdempotencyRecord("admin", "event-key-" + UUID.randomUUID(), event.eventId(),
                "event-hash", createdAt, createdAt.plus(Duration.ofHours(24)));
        return new NewEventPlan(event, idempotency, eventAudit(AuditCode.EVENT_PROVISIONING_REQUESTED, event.eventId(), createdAt),
                createdAt, "admin", event.capacity() - complimentary, complimentary, inventory.batches().size());
    }

    protected Event createdEvent(InventoryDefinition definition) {
        return createdEvent(definition, NOW.plus(Duration.ofDays(10)));
    }

    protected Event createdEvent(InventoryDefinition definition, Instant startsAt) {
        Event event = newEvent(definition, startsAt);
        assertThat(value(ports.events().create(newEventPlan(event, NOW)))).isEqualTo(TransactionOutcome.applied());
        return event;
    }

    /** Creates, provisions and enables an Event through the ports (AP-001, AP-024, AP-002, AP-003). */
    protected Event enabledEvent(InventoryDefinition definition, Instant startsAt) {
        Event event = createdEvent(definition, startsAt);
        assertThat(value(ports.events().acquireProvisioningLease(event.eventId(), OWNER, NOW.plusSeconds(60), NOW))).isTrue();
        List<Ticket> tickets = initialTickets(event);
        for (int start = 0; start < tickets.size(); start += 100) {
            value(ports.tickets().writeBatch(event, tickets.subList(start, Math.min(start + 100, tickets.size()))));
        }
        Event enabled = event.enable(event.capacity());
        assertThat(value(ports.events().enable(new EnablementPlan(event, enabled, OWNER,
                eventAudit(AuditCode.EVENT_ENABLED, event.eventId(), NOW.plusSeconds(1)), NOW.plusSeconds(1)))))
                .isEqualTo(TransactionOutcome.applied());
        return enabled;
    }

    protected static List<Ticket> initialTickets(Event event) {
        return event.inventoryDefinition().validate(event.capacity(), InventoryLimits.DEPLOYED).tickets().stream()
                .map(seed -> Ticket.provision(event.eventId(), seed))
                .toList();
    }

    protected static Order newOrder(Event event, String customerId, String... ticketIds) {
        return Order.create("order-" + UUID.randomUUID(), customerId,
                new PurchaseRequest(event.eventId(), List.of(ticketIds), "purchase-key-" + UUID.randomUUID().toString()
                        .substring(0, 8)), NOW);
    }

    protected static ReservationPlan reservationPlan(Order order) {
        IdempotencyRecord idempotency = new IdempotencyRecord(order.customerId(),
                "key-" + order.orderId().substring("order-".length(), "order-".length() + 18), order.orderId(),
                "hash-" + order.orderId(), NOW, NOW.plus(Duration.ofHours(24)));
        return new ReservationPlan(order, idempotency, audit(AuditCode.RESERVATION_CREATED, order, NOW), ActiveOrderKey.of(order));
    }

    protected Order reserve(Event event, Order order) {
        assertThat(value(ports.lifecycle().reserve(reservationPlan(order)))).isEqualTo(TransactionOutcome.applied());
        return order;
    }

    protected Order startPayment(Order order, Instant now) {
        Order started = order.startPayment(now);
        assertThat(value(ports.lifecycle().startPayment(new PaymentStartPlan(order, started, lease(now),
                audit(AuditCode.PAYMENT_STARTED, started, now), now, now.plusSeconds(15)))))
                .isEqualTo(TransactionOutcome.applied());
        return started;
    }

    protected Order confirmedOrder(Event event, String... ticketIds) {
        Order started = startPayment(reserve(event, newOrder(event, customer(), ticketIds)), NOW.plusSeconds(5));
        Order confirmed = started.recordPaymentOutcome(PaymentOutcome.APPROVED).confirm(NOW.plusSeconds(8));
        assertThat(value(ports.lifecycle().confirm(new ConfirmationPlan(started, confirmed, "ref",
                audit(AuditCode.PAYMENT_APPROVED, confirmed, NOW.plusSeconds(8)), NOW.plusSeconds(8)))))
                .isEqualTo(TransactionOutcome.applied());
        return confirmed;
    }

    protected Order failedWithReversal(Event event, String ticketId) {
        Order started = startPayment(reserve(event, newOrder(event, customer(), ticketId)), NOW.plusSeconds(5));
        Order failed = started.failProcessing(NOW.plusSeconds(9));
        assertThat(value(ports.lifecycle().close(new ClosurePlan(ClosurePlan.Kind.FAIL_PROCESSING, started, failed, null,
                audit(AuditCode.PROCESSING_FAILED, failed, NOW.plusSeconds(9), AuditCode.PAYMENT_REVERSAL_REQUESTED),
                NOW.plusSeconds(9))))).isEqualTo(TransactionOutcome.applied());
        return failed;
    }

    protected static PaymentLease lease(Instant now) {
        return new PaymentLease(OWNER, now.plusSeconds(45));
    }

    protected static AuditRecord audit(AuditCode code, Order order, Instant at, AuditCode... included) {
        AuditRecordBuilder builder = new AuditRecordBuilder()
                .code(code)
                .transitions("ST-TEST")
                .event(order.eventId())
                .order(order.orderId())
                .orderStates(OrderStatus.CREATED, order.status())
                .ticketStates(TicketState.RESERVED, TicketState.AVAILABLE)
                .tickets(order.ticketIds())
                .cause(order.failureCause() == null ? null : order.failureCause().name())
                .actor(WORKER)
                .correlation("trace-contract")
                .paymentAttempt(order.paymentAttempt() == null ? null : order.paymentAttempt().paymentAttemptId())
                .occurredAt(at);
        for (AuditCode code1 : included) {
            builder.include(code1);
        }
        return builder.build();
    }

    protected static AuditRecord eventAudit(AuditCode code, String eventId, Instant at) {
        AuditRecordBuilder builder = new AuditRecordBuilder().code(code).event(eventId)
                .actor(new Actor(ActorType.ADMIN, "admin")).correlation("trace-contract").occurredAt(at);
        if (code == AuditCode.EVENT_ENABLED) {
            builder.inventoryCounts(25, 24, 1);
        }
        return builder.build();
    }

    private List<String> pageThrough(Event event, String section, int pageSize) {
        List<String> seen = new ArrayList<>();
        Set<String> cursors = new HashSet<>();
        String cursor = null;
        do {
            AvailableTicketPage page = value(ports.tickets().findAvailablePage(event, section, pageSize, cursor));
            assertThat(page.tickets().size()).isLessThanOrEqualTo(pageSize);
            if (page.nextCursor() != null) {
                assertThat(page.tickets()).hasSize(pageSize);
                assertThat(cursors.add(page.nextCursor())).isTrue();
            }
            page.tickets().forEach(ticket -> seen.add(ticket.ticketId()));
            cursor = page.nextCursor();
        } while (cursor != null);
        return seen;
    }

    private static int capacity(InventoryDefinition definition) {
        return definition.sections().stream().flatMap(section -> section.rows().stream()).mapToInt(Row::seats).sum();
    }
}
