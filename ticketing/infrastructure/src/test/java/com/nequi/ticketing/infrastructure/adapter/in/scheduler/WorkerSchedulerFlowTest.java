package com.nequi.ticketing.infrastructure.adapter.in.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.nequi.ticketing.application.port.in.CycleRequest;
import com.nequi.ticketing.application.port.in.CycleResult;
import com.nequi.ticketing.application.port.in.Delivery;
import com.nequi.ticketing.application.port.in.ItemOutcome;
import com.nequi.ticketing.application.port.in.MessageDisposition;
import com.nequi.ticketing.application.port.in.ProcessOrderCommand;
import com.nequi.ticketing.application.port.in.PurchaseResult;
import com.nequi.ticketing.application.port.in.StartPurchaseCommand;
import com.nequi.ticketing.application.port.out.AuthorizationOutcome;
import com.nequi.ticketing.application.port.out.OrderRecord;
import com.nequi.ticketing.application.testdouble.InMemoryTicketingStore;
import com.nequi.ticketing.application.testdouble.InMemoryTicketingStore.Operation;
import com.nequi.ticketing.application.testdouble.RecordingOrderQueuePublisher;
import com.nequi.ticketing.application.testdouble.RecordingProvisioningQueuePublisher;
import com.nequi.ticketing.application.testdouble.ScriptedPaymentGateway;
import com.nequi.ticketing.application.testdouble.SequentialIdGenerator;
import com.nequi.ticketing.application.usecase.ApiUseCaseSettings;
import com.nequi.ticketing.application.usecase.EnqueueRepublishService;
import com.nequi.ticketing.application.usecase.OrderProcessingService;
import com.nequi.ticketing.application.usecase.PaymentReversalService;
import com.nequi.ticketing.application.usecase.ProvisioningCleanupService;
import com.nequi.ticketing.application.usecase.PurchaseService;
import com.nequi.ticketing.application.usecase.ReservationExpirationService;
import com.nequi.ticketing.application.usecase.WorkerUseCaseSettings;
import com.nequi.ticketing.domain.event.InventoryDefinition;
import com.nequi.ticketing.domain.event.InventoryDefinition.Row;
import com.nequi.ticketing.domain.event.InventoryDefinition.Section;
import com.nequi.ticketing.domain.order.Order;
import com.nequi.ticketing.domain.order.Order.OrderStatus;
import com.nequi.ticketing.domain.ticket.Ticket.TicketState;
import com.nequi.ticketing.infrastructure.adapter.in.sqs.CircuitGateBinding;
import com.nequi.ticketing.infrastructure.adapter.in.sqs.SwitchableConsumptionGate;
import com.nequi.ticketing.infrastructure.adapter.out.resilience.CircuitBreakerSettings;
import com.nequi.ticketing.infrastructure.adapter.out.resilience.CircuitState;
import com.nequi.ticketing.infrastructure.adapter.out.resilience.ManagedCircuitBreaker;
import com.nequi.ticketing.infrastructure.adapter.sqs.VirtualClock;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;
import reactor.test.scheduler.VirtualTimeScheduler;

/**
 * CMP-014 driving the real {@code worker} use cases (CMP-008, CMP-015, CMP-023, CMP-024) over the in-memory
 * persistence double, with virtual time for the scheduler and the application clock (ADR-028, ADR-038): release
 * deadline of a Reservation, concurrency of each process and pause of the reversals with the Payment Mock
 * circuit open.
 */
class WorkerSchedulerFlowTest {

    private static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");
    private static final String EVENT_ID = "e0000000-0000-4000-8000-000000000009";
    private static final Duration RESERVATION = Duration.ofMinutes(10);
    private static final InventoryDefinition DEFINITION = new InventoryDefinition(
            List.of(new Section("A", List.of(new Row("1", 10), new Row("2", 10), new Row("3", 10)))), List.of());

    private final VirtualTimeScheduler scheduler = VirtualTimeScheduler.create();
    private final VirtualClock clock = new VirtualClock(scheduler);
    private final InMemoryTicketingStore store = new InMemoryTicketingStore();
    private final RecordingOrderQueuePublisher orderPublisher = new RecordingOrderQueuePublisher();
    private final RecordingProvisioningQueuePublisher provisioningPublisher = new RecordingProvisioningQueuePublisher();
    private final ScriptedPaymentGateway gateway = new ScriptedPaymentGateway();
    private final WorkerUseCaseSettings settings = WorkerUseCaseSettings.deployed("worker-1");
    private final RecordingSchedulerEvents events = new RecordingSchedulerEvents();
    private final PurchaseService purchases = new PurchaseService(store, store, store, store, orderPublisher, clock,
            new SequentialIdGenerator(), ApiUseCaseSettings.DEPLOYED);
    private final OrderProcessingService processing = new OrderProcessingService(store, store, gateway, clock, settings);
    private final ReservationExpirationService expiration = new ReservationExpirationService(store, store, clock, settings);
    private final EnqueueRepublishService sweep = new EnqueueRepublishService(store, store, orderPublisher, clock, settings);
    private final PaymentReversalService reversals = new PaymentReversalService(store, store, gateway, clock, settings);
    private final ProvisioningCleanupService cleanup =
            new ProvisioningCleanupService(store, store, provisioningPublisher, clock, settings);
    private final List<PeriodicTrigger> triggers = new ArrayList<>();
    private WorkerScheduler workerScheduler;

    WorkerSchedulerFlowTest() {
        scheduler.advanceTimeTo(NOW);
        store.seedEnabledEvent(EVENT_ID, "Concert", NOW.plus(Duration.ofDays(10)), DEFINITION);
    }

    @AfterEach
    void tearDown() {
        triggers.forEach(PeriodicTrigger::dispose);
        if (workerScheduler != null) {
            workerScheduler.dispose();
        }
        scheduler.dispose();
    }

    @ParameterizedTest(name = "instance seed {0}")
    @ValueSource(longs = {1, 2, 3, 4, 5, 6})
    @DisplayName("AC-008 AC-009 FR-011 BR-030 a due Reservation is released at most 15 s after expiresAt (here within one 5 s period); never before")
    void expirationDeadline(long seed) {
        Order order = purchase(0);
        AtomicReference<Instant> closedAt = new AtomicReference<>();
        startScheduler(new SwitchableConsumptionGate(), seed);

        scheduler.advanceTimeTo(expiresAt(order).minusMillis(1));
        assertThat(status(order)).isEqualTo(OrderStatus.CREATED);
        assertThat(store.calls(Operation.CLOSE)).isZero();
        store.beforeNext(Operation.CLOSE, () -> closedAt.set(clock.now()));

        scheduler.advanceTimeBy(Duration.ofSeconds(15));

        assertThat(status(order)).isEqualTo(OrderStatus.EXPIRED);
        Duration lag = Duration.between(expiresAt(order), closedAt.get());
        assertThat(lag).isBetween(Duration.ZERO, Duration.ofMillis(4_999));
        assertThat(store.ticket(EVENT_ID, order.ticketIds().getFirst()).state()).isEqualTo(TicketState.AVAILABLE);
        assertThat(store.activeLock(order.customerId(), EVENT_ID)).isEmpty();
        assertThat(events.completed(PeriodicProcess.EXPIRATION))
                .anySatisfy(completed -> assertThat(completed.result().count(ItemOutcome.EXPIRED)).isEqualTo(1));
    }

    @Test
    @DisplayName("AC-008 ERR-003 ADR-028 rule 3 three failed expiration cycles (AP-016 unavailable) retry after 1, 2, 4 s and still release within 15 s")
    void expirationDeadlineWithFailedCycles() {
        Order order = purchase(0);
        AtomicReference<Instant> closedAt = new AtomicReference<>();
        startScheduler(new SwitchableConsumptionGate(), 21);
        scheduler.advanceTimeTo(expiresAt(order).minusMillis(1));
        store.failNext(Operation.FIND_DUE_RESERVATIONS, 3 * 8);
        store.beforeNext(Operation.CLOSE, () -> closedAt.set(clock.now()));

        scheduler.advanceTimeBy(Duration.ofSeconds(15));

        assertThat(status(order)).isEqualTo(OrderStatus.EXPIRED);
        assertThat(Duration.between(expiresAt(order), closedAt.get())).isLessThanOrEqualTo(Duration.ofSeconds(15));
        assertThat(events.failed(PeriodicProcess.EXPIRATION))
                .extracting(RecordingSchedulerEvents.Failed::nextAttemptIn)
                .containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4));
        assertThat(events.failed(PeriodicProcess.EXPIRATION)).allSatisfy(failed -> {
            assertThat(failed.cause()).isNull();
            assertThat(failed.result().count(ItemOutcome.FAILED)).isEqualTo(8);
        });
    }

    @Test
    @DisplayName("ADR-028 the expiration cycle triggered by CMP-014 runs at most 16 transitions in parallel")
    void expirationConcurrency() {
        for (int customer = 0; customer < 20; customer++) {
            purchase(customer);
        }
        scheduler.advanceTimeBy(RESERVATION);
        int callsBefore = store.waiting(Operation.CLOSE);
        store.hold(Operation.CLOSE);

        PeriodicTrigger trigger = trigger(PeriodicProcess.EXPIRATION, expiration::expireDue);
        trigger.start();
        scheduler.advanceTime();

        assertThat(store.waiting(Operation.CLOSE) - callsBefore).isEqualTo(16);
        store.release(Operation.CLOSE);
        CycleResult result = awaitCycle(PeriodicProcess.EXPIRATION);
        assertThat(result.count(ItemOutcome.EXPIRED)).isEqualTo(20);
    }

    @Test
    @DisplayName("ADR-026 ADR-028 the republish sweep triggered by CMP-014 handles at most 8 Orders in parallel")
    void republishConcurrency() {
        store.failMarkEnqueued(true);
        for (int customer = 0; customer < 10; customer++) {
            purchase(customer);
        }
        store.failMarkEnqueued(false);
        scheduler.advanceTimeBy(Duration.ofSeconds(31));
        int readsBefore = store.waiting(Operation.FIND_ORDER);
        store.hold(Operation.FIND_ORDER);

        PeriodicTrigger trigger = trigger(PeriodicProcess.REPUBLISH, sweep::republishPending);
        trigger.start();
        scheduler.advanceTime();

        assertThat(store.waiting(Operation.FIND_ORDER) - readsBefore).isEqualTo(8);
        store.release(Operation.FIND_ORDER);
        CycleResult result = awaitCycle(PeriodicProcess.REPUBLISH);
        assertThat(result.count(ItemOutcome.REPUBLISHED)).isEqualTo(10);
    }

    @Test
    @DisplayName("ADR-025 ADR-028 the reversal process triggered by CMP-014 handles at most 4 reversals in parallel")
    void reversalConcurrency() {
        for (int customer = 0; customer < 6; customer++) {
            failWithReversal(purchase(customer));
        }
        int readsBefore = store.waiting(Operation.FIND_ORDER);
        store.hold(Operation.FIND_ORDER);

        PeriodicTrigger trigger = trigger(PeriodicProcess.REVERSAL, reversals::reverseDue);
        trigger.start();
        scheduler.advanceTime();

        assertThat(store.waiting(Operation.FIND_ORDER) - readsBefore).isEqualTo(4);
        store.release(Operation.FIND_ORDER);
        CycleResult result = awaitCycle(PeriodicProcess.REVERSAL);
        assertThat(result.count(ItemOutcome.REVERSAL_CONFIRMED)).isEqualTo(6);
    }

    @Test
    @DisplayName("ADR-024 ADR-028 the provisioning cleanup triggered by CMP-014 handles at most 2 stalled Events in parallel")
    void cleanupConcurrency() {
        for (int event = 1; event <= 3; event++) {
            store.seedProvisioningEvent(SequentialIdGenerator.eventId(event), NOW.plus(Duration.ofDays(5)), DEFINITION,
                    NOW.minus(Duration.ofMinutes(4)));
        }
        int callsBefore = store.waiting(Operation.FIND_SNAPSHOT);
        store.hold(Operation.FIND_SNAPSHOT);

        PeriodicTrigger trigger = trigger(PeriodicProcess.PROVISIONING_CLEANUP, cleanup::cleanUp);
        trigger.start();
        scheduler.advanceTime();

        assertThat(store.waiting(Operation.FIND_SNAPSHOT) - callsBefore).isEqualTo(2);
        store.release(Operation.FIND_SNAPSHOT);
        CycleResult result = awaitCycle(PeriodicProcess.PROVISIONING_CLEANUP);
        assertThat(result.count(ItemOutcome.PROVISIONING_REPUBLISHED)).isEqualTo(3);
        assertThat(provisioningPublisher.published()).hasSize(3);
    }

    @Test
    @DisplayName("IV-016 FR-023 ADR-025 with the Payment Mock circuit open no reversal attempt is consumed; the reversal completes once the circuit admits probe calls")
    void reversalPausedWhileTheCircuitIsOpen() {
        Order failed = failWithReversal(purchase(0));
        ManagedCircuitBreaker circuit = ManagedCircuitBreaker.create("payment-mock", CircuitBreakerSettings.paymentMock(),
                clock, IOException.class::isInstance);
        SwitchableConsumptionGate ordersGate = new SwitchableConsumptionGate();
        SwitchableConsumptionGate reversalGate = new SwitchableConsumptionGate();
        CircuitGateBinding.bind(circuit, scheduler, ordersGate, reversalGate);
        for (int call = 0; call < 10; call++) {
            Mono.<String>error(new IOException("payment mock down")).transform(circuit.operator())
                    .onErrorResume(error -> Mono.empty()).subscribe();
        }
        assertThat(circuit.state()).isEqualTo(CircuitState.OPEN);
        startScheduler(reversalGate, 8);

        scheduler.advanceTimeBy(Duration.ofSeconds(15));

        assertThat(gateway.cancellationCalls()).isEmpty();
        assertThat(order(failed).order().reversalPlan().attempts()).isZero();
        assertThat(order(failed).order().reversalPending()).isTrue();
        assertThat(events.paused).isNotEmpty().containsOnly(PeriodicProcess.REVERSAL);
        assertThat(events.completed(PeriodicProcess.EXPIRATION)).hasSizeGreaterThanOrEqualTo(3);

        scheduler.advanceTimeBy(Duration.ofSeconds(10).plusMillis(1));

        assertThat(gateway.cancellationCalls()).containsExactly(failed.reversalPlan().paymentAttemptId());
        assertThat(order(failed).order().reversalPending()).isFalse();
        assertThat(order(failed).order().reversalPlan().completedAt()).isNotNull();
        assertThat(order(failed).order().status()).isEqualTo(OrderStatus.FAILED);
    }

    private void startScheduler(SwitchableConsumptionGate reversalGate, long seed) {
        workerScheduler = WorkerScheduler.create(expiration, sweep, reversals, cleanup, reversalGate,
                WorkerSchedulerSettings.DEPLOYED, events, scheduler, new SplittableRandom(seed));
        workerScheduler.start();
    }

    private PeriodicTrigger trigger(PeriodicProcess process, Function<CycleRequest, Mono<CycleResult>> cycle) {
        PeriodicTrigger trigger = new PeriodicTrigger(process, WorkerSchedulerSettings.DEPLOYED.of(process), cycle,
                () -> false, scheduler, events, new FixedRandom(0));
        triggers.add(trigger);
        return trigger;
    }

    private CycleResult awaitCycle(PeriodicProcess process) {
        await().atMost(Duration.ofSeconds(10)).until(() -> !events.completed(process).isEmpty());
        assertThat(events.failed(process)).isEmpty();
        return events.completed(process).getFirst().result();
    }

    /** Creates an Order in CREATED through AP-008 for {@code customer-<n>} with the n-th Ticket. */
    private Order purchase(int customer) {
        String ticketId = "A-%d-%d".formatted(customer / 10 + 1, customer % 10 + 1);
        PurchaseResult result = purchases.startPurchase(new StartPurchaseCommand("customer-" + customer, EVENT_ID,
                List.of(ticketId), "scheduler-key-%08d".formatted(customer), "trace-scheduler")).block();
        return order(result.order().orderId()).order();
    }

    /** Last reception with an unknown payment result: FAILED with the reversal mark (ADR-029, ADR-025). */
    private Order failWithReversal(Order order) {
        gateway.scriptAuthorizations(new AuthorizationOutcome.DependencyUnavailable("payment mock down"));
        MessageDisposition disposition = processing.process(
                ProcessOrderCommand.readable(order.orderId(), "trace-scheduler", new Delivery(5, true))).block();
        assertThat(disposition).isNotNull();
        Order failed = order(order.orderId()).order();
        assertThat(failed.status()).isEqualTo(OrderStatus.FAILED);
        assertThat(failed.reversalPending()).isTrue();
        return failed;
    }

    private OrderRecord order(Order order) {
        return order(order.orderId());
    }

    private OrderRecord order(String orderId) {
        return store.order(orderId).orElseThrow();
    }

    private OrderStatus status(Order order) {
        return order(order).order().status();
    }

    private static Instant expiresAt(Order order) {
        return order.reservation().expiresAt();
    }
}
