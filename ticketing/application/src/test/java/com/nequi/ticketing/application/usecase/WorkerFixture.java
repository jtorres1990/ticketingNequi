package com.nequi.ticketing.application.usecase;

import static com.nequi.ticketing.application.usecase.Rejections.value;

import com.nequi.ticketing.application.port.in.CycleRequest;
import com.nequi.ticketing.application.port.in.CycleResult;
import com.nequi.ticketing.application.port.in.Delivery;
import com.nequi.ticketing.application.port.in.MessageDisposition;
import com.nequi.ticketing.application.port.in.ProcessOrderCommand;
import com.nequi.ticketing.application.port.in.ProvisionEventCommand;
import com.nequi.ticketing.application.port.in.PurchaseResult;
import com.nequi.ticketing.application.port.in.StartPurchaseCommand;
import com.nequi.ticketing.application.port.out.OrderRecord;
import com.nequi.ticketing.application.testdouble.InMemoryTicketingStore;
import com.nequi.ticketing.application.testdouble.MutableClock;
import com.nequi.ticketing.application.testdouble.RecordingOrderQueuePublisher;
import com.nequi.ticketing.application.testdouble.RecordingProvisioningQueuePublisher;
import com.nequi.ticketing.application.testdouble.ScriptedPaymentGateway;
import com.nequi.ticketing.application.testdouble.SequentialIdGenerator;
import com.nequi.ticketing.domain.event.Event;
import com.nequi.ticketing.domain.order.Order;
import com.nequi.ticketing.domain.ticket.Ticket;
import com.nequi.ticketing.domain.ticket.Ticket.TicketState;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Wires the {@code worker} use cases over the in-memory doubles, the Payment gateway double and a
 * controlled clock. Orders are created through the real purchase use case so that every worker test
 * starts from a state produced by AP-008.
 */
final class WorkerFixture {

    static final Instant NOW = ApiFixture.NOW;
    static final String EVENT_ID = ApiFixture.EVENT_ID;
    static final String CUSTOMER_A = ApiFixture.CUSTOMER_A;
    static final String CUSTOMER_B = ApiFixture.CUSTOMER_B;
    static final String WORKER = "worker-1";
    static final String CORRELATION = "trace-worker";

    final MutableClock clock = new MutableClock(NOW);
    final SequentialIdGenerator ids = new SequentialIdGenerator();
    final InMemoryTicketingStore store = new InMemoryTicketingStore();
    final RecordingOrderQueuePublisher orderPublisher = new RecordingOrderQueuePublisher();
    final RecordingProvisioningQueuePublisher provisioningPublisher = new RecordingProvisioningQueuePublisher();
    final ScriptedPaymentGateway gateway = new ScriptedPaymentGateway();
    final WorkerUseCaseSettings settings = WorkerUseCaseSettings.deployed(WORKER);

    final PurchaseService purchases = new PurchaseService(
            store, store, store, store, orderPublisher, clock, ids, ApiUseCaseSettings.DEPLOYED);
    final OrderProcessingService processing = new OrderProcessingService(store, store, gateway, clock, settings);
    final ReservationExpirationService expiration = new ReservationExpirationService(store, store, clock, settings);
    final EnqueueRepublishService sweep = new EnqueueRepublishService(store, store, orderPublisher, clock, settings);
    final PaymentReversalService reversals = new PaymentReversalService(store, store, gateway, clock, settings);
    final EventProvisioningService provisioning = new EventProvisioningService(store, store, clock, settings);
    final ProvisioningCleanupService cleanup = new ProvisioningCleanupService(
            store, store, provisioningPublisher, clock, settings);

    Event seedEvent() {
        return store.seedEnabledEvent(EVENT_ID, "Concert", NOW.plus(Duration.ofDays(10)), ApiFixture.DEFINITION);
    }

    /** Creates an Order in CREATED through AP-008 at the current clock instant. */
    Order createOrder(String customerId, String key, String... ticketIds) {
        PurchaseResult result = value(purchases.startPurchase(
                new StartPurchaseCommand(customerId, EVENT_ID, List.of(ticketIds), key, CORRELATION)));
        return order(result.order().orderId()).order();
    }

    OrderRecord order(String orderId) {
        return store.order(orderId).orElseThrow();
    }

    Ticket ticket(String ticketId) {
        return store.ticket(EVENT_ID, ticketId);
    }

    List<TicketState> states(Order order) {
        return order.ticketIds().stream().map(id -> ticket(id).state()).toList();
    }

    MessageDisposition process(String orderId) {
        return process(orderId, 1, false);
    }

    MessageDisposition process(String orderId, int receiveCount, boolean last) {
        return value(processing.process(ProcessOrderCommand.readable(orderId, CORRELATION, new Delivery(receiveCount, last))));
    }

    MessageDisposition provision(String eventId) {
        return provision(eventId, 1, false);
    }

    MessageDisposition provision(String eventId, int receiveCount, boolean last) {
        return value(provisioning.provision(ProvisionEventCommand.readable(eventId, CORRELATION,
                new Delivery(receiveCount, last), null)));
    }

    CycleResult expire() {
        return value(expiration.expireDue(CycleRequest.of(CORRELATION)));
    }

    CycleResult sweep() {
        return value(sweep.republishPending(CycleRequest.of(CORRELATION)));
    }

    CycleResult reverse() {
        return value(reversals.reverseDue(CycleRequest.of(CORRELATION)));
    }

    CycleResult cleanUp() {
        return value(cleanup.cleanUp(CycleRequest.of(CORRELATION)));
    }

    static String key(int sequence) {
        return ApiFixture.key(sequence);
    }
}
