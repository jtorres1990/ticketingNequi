package com.nequi.ticketing.infrastructure.adapter.out.dynamodb;

import static org.assertj.core.api.Assertions.assertThat;

import com.nequi.ticketing.application.error.RequestRejectedException;
import com.nequi.ticketing.application.port.in.CreateEventCommand;
import com.nequi.ticketing.application.port.in.CycleRequest;
import com.nequi.ticketing.application.port.in.CycleResult;
import com.nequi.ticketing.application.port.in.Delivery;
import com.nequi.ticketing.application.port.in.DispositionReason;
import com.nequi.ticketing.application.port.in.EventCreationResult;
import com.nequi.ticketing.application.port.in.ItemOutcome;
import com.nequi.ticketing.application.port.in.MessageDisposition;
import com.nequi.ticketing.application.port.in.ProcessOrderCommand;
import com.nequi.ticketing.application.port.in.ProvisionEventCommand;
import com.nequi.ticketing.application.port.in.PurchaseResult;
import com.nequi.ticketing.application.port.in.StartPurchaseCommand;
import com.nequi.ticketing.application.port.out.ClosurePlan;
import com.nequi.ticketing.application.port.out.ConfirmationPlan;
import com.nequi.ticketing.application.port.out.IdGenerator;
import com.nequi.ticketing.application.port.out.OrderRecord;
import com.nequi.ticketing.application.port.out.TransactionOutcome;
import com.nequi.ticketing.application.testdouble.MutableClock;
import com.nequi.ticketing.application.testdouble.RecordingOrderQueuePublisher;
import com.nequi.ticketing.application.testdouble.RecordingProvisioningQueuePublisher;
import com.nequi.ticketing.application.testdouble.ScriptedPaymentGateway;
import com.nequi.ticketing.application.usecase.ApiUseCaseSettings;
import com.nequi.ticketing.application.usecase.EventManagementService;
import com.nequi.ticketing.application.usecase.EventProvisioningService;
import com.nequi.ticketing.application.usecase.OrderProcessingService;
import com.nequi.ticketing.application.usecase.PurchaseService;
import com.nequi.ticketing.application.usecase.ReservationExpirationService;
import com.nequi.ticketing.application.usecase.WorkerUseCaseSettings;
import com.nequi.ticketing.domain.audit.AuditRecord;
import com.nequi.ticketing.domain.audit.AuditRecord.AuditCode;
import com.nequi.ticketing.domain.error.DomainErrorCode;
import com.nequi.ticketing.domain.event.Event.ProvisioningStatus;
import com.nequi.ticketing.domain.event.InventoryDefinition;
import com.nequi.ticketing.domain.event.InventoryDefinition.ComplimentaryRange;
import com.nequi.ticketing.domain.event.InventoryDefinition.Row;
import com.nequi.ticketing.domain.event.InventoryDefinition.Section;
import com.nequi.ticketing.domain.order.Order;
import com.nequi.ticketing.domain.order.Order.OrderStatus;
import com.nequi.ticketing.domain.order.Order.PaymentOutcome;
import com.nequi.ticketing.domain.ticket.Ticket.TicketState;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * ADR-038 deterministic concurrency and mechanism tests on DynamoDB Local (INC-005), driven through the
 * real use cases of INC-003 / INC-004 wired over the DynamoDB adapter, with real contention: every
 * contender is subscribed at once on {@code Schedulers.parallel()} and the SDK sends the requests
 * concurrently. Each scenario uses its own table.
 */
class DynamoDbMechanismIT {

    private static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");
    private static final String CORRELATION = "trace-it";

    /** A: rows 1 and 2 with 10 seats each; B: row 1 with 5 seats, seat 5 complimentary. Capacity 25. */
    private static final InventoryDefinition DEFINITION = new InventoryDefinition(
            List.of(new Section("A", List.of(new Row("1", 10), new Row("2", 10))),
                    new Section("B", List.of(new Row("1", 5)))),
            List.of(new ComplimentaryRange("B", "1", 5, 5)));

    private DynamoDbAsyncClient client;
    private String table;
    private DynamoDbPersistence persistence;
    private MutableClock clock;
    private RecordingOrderQueuePublisher orderPublisher;
    private RecordingProvisioningQueuePublisher provisioningPublisher;
    private ScriptedPaymentGateway gateway;
    private PurchaseService purchases;
    private EventManagementService eventManagement;
    private EventProvisioningService provisioning;
    private OrderProcessingService processing;
    private ReservationExpirationService expiration;

    @BeforeEach
    void wire() {
        client = DynamoDbLocalSupport.client();
        table = DynamoDbLocalSupport.createTable();
        clock = new MutableClock(NOW);
        persistence = DynamoDbPersistence.create(client, DynamoDbAdapterSettings.deployed(table), clock);
        orderPublisher = new RecordingOrderQueuePublisher();
        provisioningPublisher = new RecordingProvisioningQueuePublisher();
        gateway = new ScriptedPaymentGateway();
        IdGenerator ids = new IdGenerator() {
            @Override
            public String newOrderId() {
                return UUID.randomUUID().toString();
            }

            @Override
            public String newEventId() {
                return UUID.randomUUID().toString();
            }
        };
        WorkerUseCaseSettings worker = WorkerUseCaseSettings.deployed("worker-it");
        purchases = new PurchaseService(persistence.eventCatalog(), persistence.orderLifecycleStore(),
                persistence.orderReader(), persistence.idempotencyStore(), orderPublisher, clock, ids,
                ApiUseCaseSettings.DEPLOYED);
        eventManagement = new EventManagementService(persistence.eventCatalog(), persistence.idempotencyStore(),
                provisioningPublisher, clock, ids, ApiUseCaseSettings.DEPLOYED);
        provisioning = new EventProvisioningService(persistence.eventCatalog(), persistence.ticketInventory(), clock, worker);
        processing = new OrderProcessingService(persistence.orderReader(), persistence.orderLifecycleStore(), gateway,
                clock, worker);
        expiration = new ReservationExpirationService(persistence.orderReader(), persistence.orderLifecycleStore(), clock,
                worker);
    }

    // ------------------------------------------------------------------ provisioning (ADR-024)

    @Test
    @DisplayName("AC-001 AP-001 AP-002 AP-024 AP-025 AP-003 an Event created and provisioned by the use cases over DynamoDB becomes ENABLED with exactly its capacity")
    void createAndProvision() {
        String eventId = enabledEvent();

        assertThat(persistence.eventCatalog().findProvisioningSnapshot(eventId).block().event().provisioningStatus())
                .isEqualTo(ProvisioningStatus.ENABLED);
        assertThat(persistence.ticketInventory().countAvailable(persistence.eventCatalog().findEvent(eventId).block())
                .block()).isEqualTo(24);
        assertThat(ticketState(eventId, "B-1-5")).contains(TicketState.COMPLIMENTARY);
        assertThat(persistence.auditTrail().eventAudit(eventId).map(AuditRecord::code).collectList().block())
                .containsExactlyInAnyOrder(AuditCode.EVENT_PROVISIONING_REQUESTED, AuditCode.EVENT_ENABLED);
    }

    @Test
    @DisplayName("ADR-038 ADR-024 provisioning redelivery after enabling and reserving takes no lease and changes no Ticket; an expired foreign lease is reclaimed")
    void provisioningRedelivery() {
        String eventId = enabledEvent();
        PurchaseResult bought = purchase("customer-1", key(), eventId, "A-1-1").block();

        MessageDisposition redelivered = provision(eventId);
        assertThat(redelivered).isInstanceOf(MessageDisposition.Delete.class);
        assertThat(persistence.eventCatalog().findProvisioningSnapshot(eventId).block().leaseOwner()).isNull();
        assertThat(ticketState(eventId, "A-1-1")).contains(TicketState.RESERVED);
        assertThat(item(Keys.ticket(eventId, "A-1-1")).get("orderId").s()).isEqualTo(bought.order().orderId());

        String other = createEvent();
        assertThat(persistence.eventCatalog().acquireProvisioningLease(other, "foreign/1", NOW.plusSeconds(10), NOW).block())
                .isTrue();
        clock.set(NOW.plusSeconds(11));
        assertThat(provision(other)).isEqualTo(MessageDisposition.delete(DispositionReason.ENABLED));
        clock.set(NOW);
    }

    // ------------------------------------------------------------------ purchase races (ADR-023, ADR-027, ADR-032)

    @RepeatedTest(5)
    @DisplayName("AC-007 VAL-004 ADR-038 simultaneous purchases of the same Ticket on DynamoDB: one winner, the rest rejected, no partial reservation")
    void oversellingSameTicket() {
        String eventId = enabledEvent();
        List<Object> results = race(IntStream.range(0, 8)
                .mapToObj(index -> purchase("customer-" + index, key(), eventId, "A-1-1", "A-1-" + (index + 2)))
                .toList());

        List<PurchaseResult> winners = results.stream().filter(PurchaseResult.class::isInstance)
                .map(PurchaseResult.class::cast).toList();
        assertThat(winners).hasSize(1);
        assertThat(results.stream().filter(DomainErrorCode.class::isInstance))
                .hasSize(7)
                .allMatch(code -> code == DomainErrorCode.TICKETS_UNAVAILABLE || code == DomainErrorCode.SERVICE_UNAVAILABLE);
        String winner = winners.getFirst().order().orderId();
        assertThat(item(Keys.ticket(eventId, "A-1-1")).get("orderId").s()).isEqualTo(winner);
        for (int index = 0; index < 8; index++) {
            String companion = "A-1-" + (index + 2);
            boolean owned = winners.getFirst().order().ticketIds().contains(companion);
            assertThat(ticketState(eventId, companion)).contains(owned ? TicketState.RESERVED : TicketState.AVAILABLE);
        }
    }

    @RepeatedTest(3)
    @DisplayName("AC-007 AC-014 ADR-038 overlapping Ticket sets bought at once: no Ticket in two Orders, every Order holds all its Tickets")
    void overlappingSets() {
        String eventId = enabledEvent();
        List<Object> results = race(IntStream.range(1, 10)
                .mapToObj(index -> purchase("customer-" + index, key(), eventId, "A-1-" + index, "A-1-" + (index + 1)))
                .toList());

        Map<String, String> owners = new HashMap<>();
        for (Object result : results) {
            if (result instanceof PurchaseResult created) {
                for (String ticketId : created.order().ticketIds()) {
                    assertThat(owners.put(ticketId, created.order().orderId())).as("Ticket in two Orders").isNull();
                    assertThat(item(Keys.ticket(eventId, ticketId)).get("orderId").s()).isEqualTo(created.order().orderId());
                }
            }
        }
        for (int seat = 1; seat <= 10; seat++) {
            String ticketId = "A-1-" + seat;
            assertThat(ticketState(eventId, ticketId))
                    .contains(owners.containsKey(ticketId) ? TicketState.RESERVED : TicketState.AVAILABLE);
        }
        assertThat(owners).isNotEmpty();
    }

    @RepeatedTest(3)
    @DisplayName("ADR-027 ADR-038 two simultaneous requests with the same Idempotency-Key and content produce the same Order")
    void sameKeySameContent() {
        String eventId = enabledEvent();
        String key = key();
        List<Object> results = race(List.of(purchase("customer-1", key, eventId, "A-2-1"),
                purchase("customer-1", key, eventId, "A-2-1")));

        assertThat(results).allMatch(PurchaseResult.class::isInstance);
        List<PurchaseResult> created = results.stream().map(PurchaseResult.class::cast).toList();
        assertThat(created.get(0).order().orderId()).isEqualTo(created.get(1).order().orderId());
        assertThat(created).filteredOn(PurchaseResult::replayed).hasSize(1);
        assertThat(persistence.idempotencyStore().findPurchase("customer-1", key).block().resourceId())
                .isEqualTo(created.getFirst().order().orderId());
    }

    @RepeatedTest(3)
    @DisplayName("ADR-027 ADR-038 two simultaneous requests with the same key and different content: one Order and IDEMPOTENCY_KEY_REUSED, never TICKETS_UNAVAILABLE")
    void sameKeyDifferentContent() {
        String eventId = enabledEvent();
        String key = key();
        List<Object> results = race(List.of(purchase("customer-1", key, eventId, "A-2-2"),
                purchase("customer-1", key, eventId, "A-2-2", "A-2-3")));

        assertThat(results).filteredOn(PurchaseResult.class::isInstance).hasSize(1);
        assertThat(results).filteredOn(DomainErrorCode.class::isInstance)
                .containsExactly(DomainErrorCode.IDEMPOTENCY_KEY_REUSED);
    }

    @RepeatedTest(3)
    @DisplayName("AC-044 AC-043 AC-045 BR-024 ADR-032 simultaneous purchases of one customer for one Event: one Order, ACTIVE_ORDER_EXISTS for the rest; after expiring, a new purchase succeeds")
    void oneActiveOrderPerCustomerAndEvent() {
        String eventId = enabledEvent();
        List<Object> results = race(IntStream.range(1, 6)
                .mapToObj(index -> purchase("customer-1", key(), eventId, "A-2-" + index))
                .toList());

        List<PurchaseResult> created = results.stream().filter(PurchaseResult.class::isInstance)
                .map(PurchaseResult.class::cast).toList();
        assertThat(created).hasSize(1);
        assertThat(results).filteredOn(DomainErrorCode.class::isInstance).hasSize(4)
                .allMatch(code -> code == DomainErrorCode.ACTIVE_ORDER_EXISTS);
        String orderId = created.getFirst().order().orderId();
        assertThat(item(Keys.activeOrder("customer-1", eventId)).get("orderId").s()).isEqualTo(orderId);

        clock.set(NOW.plus(Order.RESERVATION_DURATION).plusSeconds(1));
        CycleResult cycle = expiration.expireDue(CycleRequest.of(CORRELATION)).block();
        assertThat(cycle.count(ItemOutcome.EXPIRED)).isGreaterThanOrEqualTo(1);
        assertThat(persistence.orderReader().findById(orderId).block().order().status()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(item(Keys.activeOrder("customer-1", eventId))).isEmpty();
        PurchaseResult again = purchase("customer-1", key(), eventId, "A-2-1").block();
        assertThat(again.order().status()).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    @DisplayName("AC-004 AC-014 AC-016 ADR-023 a reservation of 10 Tickets (14 items) is all or nothing on DynamoDB")
    void tenTicketReservationIsAtomic() {
        String eventId = enabledEvent();
        purchase("customer-0", key(), eventId, "A-2-10").block();
        String[] tickets = IntStream.rangeClosed(1, 10).mapToObj(seat -> "A-2-" + seat).toArray(String[]::new);

        Object rejected = race(List.of(purchase("customer-1", key(), eventId, tickets))).getFirst();
        assertThat(rejected).isEqualTo(DomainErrorCode.TICKETS_UNAVAILABLE);
        for (int seat = 1; seat <= 9; seat++) {
            assertThat(ticketState(eventId, "A-2-" + seat)).contains(TicketState.AVAILABLE);
        }
        String[] free = IntStream.rangeClosed(1, 10).mapToObj(seat -> "A-1-" + seat).toArray(String[]::new);
        PurchaseResult created = purchase("customer-2", key(), eventId, free).block();
        assertThat(created.order().ticketIds()).hasSize(10);
        assertThat(IntStream.rangeClosed(1, 10).mapToObj(seat -> ticketState(eventId, "A-1-" + seat)))
                .allMatch(state -> state.equals(Optional.of(TicketState.RESERVED)));
    }

    // ------------------------------------------------------------------ worker races (ADR-008, ADR-025)

    @Test
    @DisplayName("AC-005 AC-019 MSG-001 processing an Order over DynamoDB starts the payment, authorizes once and confirms with every Ticket SOLD")
    void processingConfirms() {
        String eventId = enabledEvent();
        PurchaseResult bought = purchase("customer-1", key(), eventId, "B-1-1", "B-1-2").block();

        MessageDisposition disposition = processing.process(ProcessOrderCommand.readable(bought.order().orderId(),
                CORRELATION, new Delivery(1, false))).block();
        assertThat(disposition).isInstanceOf(MessageDisposition.Delete.class);
        OrderRecord record = persistence.orderReader().findById(bought.order().orderId()).block();
        assertThat(record.order().status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(gateway.authorizationsOf(record.order().paymentAttempt().paymentAttemptId())).isEqualTo(1);
        assertThat(ticketState(eventId, "B-1-1")).contains(TicketState.SOLD);
        assertThat(ticketState(eventId, "B-1-2")).contains(TicketState.SOLD);
        assertThat(item(Keys.activeOrder("customer-1", eventId))).isEmpty();
        assertThat(persistence.auditTrail().orderAudit(bought.order().orderId()).map(AuditRecord::code).collectList().block())
                .containsExactlyInAnyOrder(AuditCode.RESERVATION_CREATED, AuditCode.PAYMENT_STARTED, AuditCode.PAYMENT_APPROVED);
    }

    @RepeatedTest(10)
    @DisplayName("AC-050 ADR-008 ADR-038 confirming and expiring the same Order at once: exactly one terminal transition, never a partial one")
    void confirmVersusExpire() {
        String eventId = enabledEvent();
        String orderId = purchase("customer-1", key(), eventId, "A-1-5", "A-1-6").block().order().orderId();
        Order current = persistence.orderReader().findById(orderId).block().order();
        Order started = current.startPayment(NOW.plusSeconds(5));
        assertThat(persistence.orderLifecycleStore().startPayment(new com.nequi.ticketing.application.port.out.PaymentStartPlan(
                current, started, new com.nequi.ticketing.application.port.out.PaymentLease("w/1", NOW.plusSeconds(50)),
                PersistencePortContract.audit(AuditCode.PAYMENT_STARTED, started, NOW.plusSeconds(5)), NOW.plusSeconds(5),
                NOW.plusSeconds(20))).block()).isEqualTo(TransactionOutcome.applied());
        Instant expiry = started.reservation().expiresAt();
        Order confirmed = started.recordPaymentOutcome(PaymentOutcome.APPROVED).confirm(expiry.minusMillis(1));
        Order expired = started.expire(expiry);

        List<Object> outcomes = race(List.of(
                persistence.orderLifecycleStore().confirm(new ConfirmationPlan(started, confirmed, "ref",
                        PersistencePortContract.audit(AuditCode.PAYMENT_APPROVED, confirmed, expiry.minusMillis(1)),
                        expiry.minusMillis(1))),
                persistence.orderLifecycleStore().close(new ClosurePlan(ClosurePlan.Kind.EXPIRE, started, expired, null,
                        PersistencePortContract.audit(AuditCode.RESERVATION_EXPIRED, expired, expiry,
                                AuditCode.PAYMENT_REVERSAL_REQUESTED), expiry))));

        assertThat(outcomes).filteredOn(TransactionOutcome.applied()::equals).hasSize(1);
        Order stored = persistence.orderReader().findById(orderId).block().order();
        TicketState expectedState = stored.status() == OrderStatus.CONFIRMED ? TicketState.SOLD : TicketState.AVAILABLE;
        assertThat(stored.status()).isIn(OrderStatus.CONFIRMED, OrderStatus.EXPIRED);
        assertThat(ticketState(eventId, "A-1-5")).contains(expectedState);
        assertThat(ticketState(eventId, "A-1-6")).contains(expectedState);
        assertThat(stored.reversalPending()).isEqualTo(stored.status() == OrderStatus.EXPIRED);
        assertThat(item(Keys.activeOrder("customer-1", eventId))).isEmpty();
    }

    @RepeatedTest(10)
    @DisplayName("SPK-009 ADR-025 a terminal transaction and the simple conditional write AP-011 on the same Order are serialized")
    void terminalTransactionVersusMarkEnqueued() {
        String eventId = enabledEvent();
        Order current = PersistencePortContract.newOrder(persistence.eventCatalog().findEvent(eventId).block(),
                "customer-1", "A-1-1");
        assertThat(persistence.orderLifecycleStore().reserve(PersistencePortContract.reservationPlan(current)).block())
                .isEqualTo(TransactionOutcome.applied());
        Instant expiry = current.reservation().expiresAt();
        Order expired = current.expire(expiry);

        List<Object> outcomes = race(List.of(
                persistence.orderLifecycleStore().markEnqueued(current.orderId(), NOW.plusSeconds(2)),
                persistence.orderLifecycleStore().close(new ClosurePlan(ClosurePlan.Kind.EXPIRE, current, expired, null,
                        PersistencePortContract.audit(AuditCode.RESERVATION_EXPIRED, expired, expiry), expiry))));

        assertThat(outcomes.get(1)).isEqualTo(TransactionOutcome.applied());
        Map<String, AttributeValue> stored = item(Keys.order(current.orderId()));
        assertThat(stored.get("status").s()).isEqualTo("EXPIRED");
        assertThat(stored.containsKey("enqueuedAt")).isEqualTo(Boolean.TRUE.equals(outcomes.get(0)));
        assertThat(stored).doesNotContainKeys("GSI4PK", "GSI3PK");
        assertThat(ticketState(eventId, "A-1-1")).contains(TicketState.AVAILABLE);
    }

    @Test
    @DisplayName("ADR-025 ADR-038 AC-006 expiring an Order whose Ticket condition fails quarantines it: lock and Tickets retained, audit written, invisible to API-005")
    void expirationQuarantine() {
        String eventId = enabledEvent();
        String orderId = purchase("customer-1", key(), eventId, "A-2-4", "A-2-5").block().order().orderId();
        client.updateItem(builder -> builder.tableName(table).key(Keys.meta(Keys.ticket(eventId, "A-2-5")))
                .updateExpression("SET #s = :sold").expressionAttributeNames(Map.of("#s", "state"))
                .expressionAttributeValues(Map.of(":sold", AttributeValue.fromS("SOLD")))).join();
        clock.set(NOW.plus(Order.RESERVATION_DURATION).plusSeconds(1));

        CycleResult cycle = expiration.expireDue(CycleRequest.of(CORRELATION)).block();
        assertThat(cycle.count(ItemOutcome.QUARANTINED)).isEqualTo(1);
        Order stored = persistence.orderReader().findById(orderId).block().order();
        assertThat(stored.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(stored.quarantinedAt()).isNotNull();
        assertThat(item(Keys.activeOrder("customer-1", eventId)).get("orderId").s()).isEqualTo(orderId);
        assertThat(ticketState(eventId, "A-2-4")).contains(TicketState.RESERVED);
        assertThat(item(Keys.order(orderId)).get("GSI3PK").s()).isEqualTo(Keys.REVIEW_QUARANTINE);
        assertThat(persistence.auditTrail().orderAudit(orderId).map(AuditRecord::code).collectList().block())
                .contains(AuditCode.ORDER_QUARANTINED);
        assertThat(expiration.expireDue(CycleRequest.of(CORRELATION)).block().count(ItemOutcome.QUARANTINED)).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private String createEvent() {
        EventCreationResult created = eventManagement.createEvent(new CreateEventCommand("admin", key(), "Concert", "Arena",
                NOW.plus(Duration.ofDays(10)), 25, DEFINITION, CORRELATION)).block();
        return created.status().eventId();
    }

    private String enabledEvent() {
        String eventId = createEvent();
        assertThat(provision(eventId)).isInstanceOf(MessageDisposition.Delete.class);
        return eventId;
    }

    private MessageDisposition provision(String eventId) {
        return provisioning.provision(ProvisionEventCommand.readable(eventId, CORRELATION, new Delivery(1, false), null))
                .block();
    }

    private Mono<PurchaseResult> purchase(String customerId, String key, String eventId, String... ticketIds) {
        return purchases.startPurchase(new StartPurchaseCommand(customerId, eventId, List.of(ticketIds), key, CORRELATION));
    }

    /** Subscribes every contender at once on the parallel scheduler; rejections become their code. */
    private static List<Object> race(List<? extends Mono<?>> contenders) {
        return Flux.fromIterable(contenders)
                .flatMapSequential(contender -> contender
                        .<Object>map(value -> value)
                        .onErrorResume(RequestRejectedException.class, rejected -> Mono.just(rejected.code()))
                        .subscribeOn(Schedulers.parallel()), contenders.size())
                .collectList()
                .block(Duration.ofSeconds(60));
    }

    private Optional<TicketState> ticketState(String eventId, String ticketId) {
        return Optional.ofNullable(item(Keys.ticket(eventId, ticketId)).get(TicketItems.STATE))
                .map(value -> TicketState.valueOf(value.s()));
    }

    private Map<String, AttributeValue> item(String partitionKey) {
        return client.getItem(builder -> builder.tableName(table).key(Keys.meta(partitionKey)).consistentRead(true))
                .join().item();
    }

    private static String key() {
        return "it-key-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

}
