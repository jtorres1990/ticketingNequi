package com.nequi.ticketing.application.usecase;

import static com.nequi.ticketing.application.usecase.ApiFixture.CUSTOMER_A;
import static com.nequi.ticketing.application.usecase.ApiFixture.DEFINITION;
import static com.nequi.ticketing.application.usecase.ApiFixture.EVENT_ID;
import static com.nequi.ticketing.application.usecase.ApiFixture.NOW;
import static com.nequi.ticketing.application.usecase.ApiFixture.command;
import static com.nequi.ticketing.application.usecase.ApiFixture.key;
import static com.nequi.ticketing.application.usecase.Rejections.rejected;
import static com.nequi.ticketing.application.usecase.Rejections.value;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nequi.ticketing.application.port.in.PurchaseResult;
import com.nequi.ticketing.application.port.out.EventCatalog;
import com.nequi.ticketing.application.port.out.FailedItem;
import com.nequi.ticketing.application.port.out.IdempotencyStore;
import com.nequi.ticketing.application.port.out.ItemFailure;
import com.nequi.ticketing.application.port.out.OrderLifecycleStore;
import com.nequi.ticketing.application.port.out.OrderQueuePublisher;
import com.nequi.ticketing.application.port.out.OrderReader;
import com.nequi.ticketing.application.port.out.OrderRecord;
import com.nequi.ticketing.application.port.out.PublishResult;
import com.nequi.ticketing.application.port.out.PublisherAvailability;
import com.nequi.ticketing.application.port.out.TransactionOutcome;
import com.nequi.ticketing.application.testdouble.MutableClock;
import com.nequi.ticketing.application.testdouble.SequentialIdGenerator;
import com.nequi.ticketing.domain.error.DomainErrorCode;
import com.nequi.ticketing.domain.event.Event;
import com.nequi.ticketing.domain.event.InventoryLimits;
import com.nequi.ticketing.domain.order.Order;
import com.nequi.ticketing.domain.order.Order.OrderStatus;
import com.nequi.ticketing.domain.order.PurchaseRequest;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/** ADR-026 / ADR-025 defensive paths of the purchase compensation, driven with Mockito port mocks. */
class PurchaseCompensationEdgeTest {

    private static final TransactionOutcome TICKET_CONDITION = TransactionOutcome.cancelled(List.of(
            ItemFailure.ticket(FailedItem.TICKET_STATE, "A-1-1")));

    private final EventCatalog catalog = mock(EventCatalog.class);
    private final OrderLifecycleStore lifecycle = mock(OrderLifecycleStore.class);
    private final OrderReader reader = mock(OrderReader.class);
    private final IdempotencyStore idempotency = mock(IdempotencyStore.class);
    private final OrderQueuePublisher publisher = mock(OrderQueuePublisher.class);
    private PurchaseService service;

    @BeforeEach
    void setUp() {
        Event event = Event.create(EVENT_ID, "Concert", "Arena", NOW.plus(Duration.ofDays(5)), 25, DEFINITION,
                NOW, InventoryLimits.DEPLOYED).enable(25);
        when(catalog.findEvent(EVENT_ID)).thenReturn(Mono.just(event));
        when(idempotency.findPurchase(anyString(), anyString())).thenReturn(Mono.empty());
        when(publisher.availability()).thenReturn(PublisherAvailability.available());
        when(lifecycle.reserve(any())).thenReturn(Mono.just(TransactionOutcome.applied()));
        service = new PurchaseService(catalog, lifecycle, reader, idempotency, publisher,
                new MutableClock(NOW), new SequentialIdGenerator(), ApiUseCaseSettings.DEPLOYED);
    }

    @Test
    @DisplayName("ADR-026 an empty publication result is a definitive enqueue failure")
    void emptyPublicationIsAFailure() {
        when(publisher.publish(any())).thenReturn(Mono.empty());
        when(lifecycle.failEnqueue(any())).thenReturn(Mono.just(TransactionOutcome.applied()));

        PurchaseResult result = value(service.startPurchase(command(CUSTOMER_A, key(1), "A-1-1")));

        assertThat(result.order().status()).isEqualTo(OrderStatus.FAILED);
        verify(lifecycle, never()).markEnqueued(anyString(), any());
    }

    @Test
    @DisplayName("ADR-026 an empty enqueue marker result never fails the purchase")
    void emptyEnqueueMarkerIsIgnored() {
        when(publisher.publish(any())).thenReturn(Mono.just(PublishResult.PUBLISHED));
        when(lifecycle.markEnqueued(anyString(), any())).thenReturn(Mono.empty());

        assertThat(value(service.startPurchase(command(CUSTOMER_A, key(1), "A-1-1"))).order().status())
                .isEqualTo(OrderStatus.CREATED);
    }

    @Test
    @DisplayName("ADR-026 a compensation that fails with a technical error is a double failure")
    void compensationErrorIsADoubleFailure() {
        when(publisher.publish(any())).thenReturn(Mono.just(PublishResult.FAILED));
        when(lifecycle.failEnqueue(any())).thenReturn(Mono.error(new IllegalStateException("store down")));

        rejected(service.startPurchase(command(CUSTOMER_A, key(1), "A-1-1")), DomainErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("ADR-026 a cancelled compensation whose Order cannot be re-read is a double failure")
    void missingOrderOnReReadIsADoubleFailure() {
        when(publisher.publish(any())).thenReturn(Mono.just(PublishResult.FAILED));
        when(lifecycle.failEnqueue(any())).thenReturn(Mono.just(TICKET_CONDITION));
        when(reader.findById(anyString())).thenReturn(Mono.empty());

        rejected(service.startPurchase(command(CUSTOMER_A, key(1), "A-1-1")), DomainErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("ADR-026 a cancelled compensation whose re-read fails is a double failure")
    void reReadErrorIsADoubleFailure() {
        when(publisher.publish(any())).thenReturn(Mono.just(PublishResult.FAILED));
        when(lifecycle.failEnqueue(any())).thenReturn(Mono.just(TICKET_CONDITION));
        when(reader.findById(anyString())).thenReturn(Mono.error(new IllegalStateException("store down")));

        rejected(service.startPurchase(command(CUSTOMER_A, key(1), "A-1-1")), DomainErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("ADR-025 ADR-026 a quarantine that is not applied leaves a double failure")
    void quarantineNotAppliedIsADoubleFailure() {
        when(publisher.publish(any())).thenReturn(Mono.just(PublishResult.FAILED));
        when(lifecycle.failEnqueue(any())).thenReturn(Mono.just(TICKET_CONDITION));
        when(reader.findById(anyString())).thenReturn(Mono.just(createdRecord()));
        when(lifecycle.quarantine(any())).thenReturn(
                Mono.just(TransactionOutcome.cancelled(List.of(ItemFailure.of(FailedItem.ORDER)))));

        rejected(service.startPurchase(command(CUSTOMER_A, key(1), "A-1-1")), DomainErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("ADR-025 ADR-026 a quarantine that fails with a technical error leaves a double failure")
    void quarantineErrorIsADoubleFailure() {
        when(publisher.publish(any())).thenReturn(Mono.just(PublishResult.FAILED));
        when(lifecycle.failEnqueue(any())).thenReturn(Mono.just(TICKET_CONDITION));
        when(reader.findById(anyString())).thenReturn(Mono.just(createdRecord()));
        when(lifecycle.quarantine(any())).thenReturn(Mono.error(new IllegalStateException("store down")));

        rejected(service.startPurchase(command(CUSTOMER_A, key(1), "A-1-1")), DomainErrorCode.SERVICE_UNAVAILABLE);
    }

    private static OrderRecord createdRecord() {
        Order order = Order.create(SequentialIdGenerator.orderId(1), CUSTOMER_A,
                new PurchaseRequest(EVENT_ID, List.of("A-1-1"), key(1)), NOW);
        return new OrderRecord(order, NOW, NOW, null);
    }
}
