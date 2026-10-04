package com.nequi.ticketing.application.usecase;

import static com.nequi.ticketing.application.usecase.WorkerFixture.CUSTOMER_A;
import static com.nequi.ticketing.application.usecase.WorkerFixture.CUSTOMER_B;
import static com.nequi.ticketing.application.usecase.WorkerFixture.NOW;
import static com.nequi.ticketing.application.usecase.WorkerFixture.key;
import static org.assertj.core.api.Assertions.assertThat;

import com.nequi.ticketing.application.port.in.CycleResult;
import com.nequi.ticketing.application.port.in.ItemOutcome;
import com.nequi.ticketing.application.port.out.OrderProcessingRequested;
import com.nequi.ticketing.application.port.out.OrderRecord;
import com.nequi.ticketing.application.port.out.PublishResult;
import com.nequi.ticketing.application.port.out.PublisherAvailability;
import com.nequi.ticketing.application.testdouble.InMemoryTicketingStore.Operation;
import com.nequi.ticketing.domain.order.Order;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CMP-023 and the ADR-038 mechanism "sweep versus synchronous path". */
class EnqueueRepublishServiceTest {

    private WorkerFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new WorkerFixture();
        fixture.seedEvent();
    }

    /** Creates an Order whose synchronous publication failed twice (publication and compensation). */
    private Order pendingOrder(String customerId, int sequence, String ticketId) {
        Order order = fixture.createOrder(customerId, key(sequence), ticketId);
        OrderRecord stored = fixture.order(order.orderId());
        fixture.store.putOrder(new OrderRecord(stored.order(), stored.createdAt(), stored.updatedAt(), null));
        return order;
    }

    @Test
    @DisplayName("ADR-038/mechanism ADR-026 an Order younger than 30 s is never republished: the synchronous path owns it")
    void youngOrderIsNotRepublished() {
        Order order = pendingOrder(CUSTOMER_A, 1, "A-1-1");
        int published = fixture.orderPublisher.published().size();
        fixture.clock.advance(Duration.ofSeconds(30));

        CycleResult result = fixture.sweep();

        assertThat(result.total()).isZero();
        assertThat(fixture.orderPublisher.published()).hasSize(published);
        assertThat(fixture.order(order.orderId()).enqueued()).isFalse();
    }

    @Test
    @DisplayName("ADR-038/mechanism ADR-026 FR-005 ALT-006 an Order older than 30 s without enqueuedAt is republished and marked")
    void oldPendingOrderIsRepublished() {
        Order order = pendingOrder(CUSTOMER_A, 1, "A-1-1");
        int published = fixture.orderPublisher.published().size();
        fixture.clock.advance(Duration.ofSeconds(31));

        CycleResult result = fixture.sweep();

        assertThat(result.count(ItemOutcome.REPUBLISHED)).isEqualTo(1);
        OrderProcessingRequested message = fixture.orderPublisher.published().getLast();
        assertThat(fixture.orderPublisher.published()).hasSize(published + 1);
        assertThat(message.orderId()).isEqualTo(order.orderId());
        assertThat(message.publisher()).isEqualTo(OrderProcessingRequested.Publisher.SWEEP);
        assertThat(message.correlationId()).isEqualTo(WorkerFixture.CORRELATION);
        assertThat(message.occurredAt()).isEqualTo(NOW);
        assertThat(fixture.order(order.orderId()).enqueuedAt()).isEqualTo(NOW.plusSeconds(31));
        assertThat(fixture.store.audits()).filteredOn(audit -> order.orderId().equals(audit.orderId())).hasSize(1);
        assertThat(fixture.sweep().total()).isZero();
    }

    @Test
    @DisplayName("ADR-038/mechanism ADR-026 BR-029 an Order with less than the 15 s cutoff left is left to expiration")
    void orderInsideTheCutoffIsNotRepublished() {
        Order order = pendingOrder(CUSTOMER_A, 1, "A-1-1");
        fixture.clock.set(order.reservation().expiresAt().minusSeconds(15).plusMillis(1));

        assertThat(fixture.sweep().count(ItemOutcome.NOT_APPLICABLE)).isEqualTo(1);

        fixture.clock.set(order.reservation().expiresAt().minusSeconds(15));
        assertThat(fixture.sweep().count(ItemOutcome.REPUBLISHED)).isEqualTo(1);
    }

    @Test
    @DisplayName("ADR-035 ADR-026 with the publication circuit open the sweep skips the whole cycle")
    void openCircuitSkipsTheCycle() {
        pendingOrder(CUSTOMER_A, 1, "A-1-1");
        fixture.clock.advance(Duration.ofMinutes(1));
        fixture.orderPublisher.setAvailability(PublisherAvailability.unavailable(Duration.ofSeconds(4)));

        CycleResult result = fixture.sweep();

        assertThat(result.skipped()).isTrue();
        assertThat(result.total()).isZero();
    }

    @Test
    @DisplayName("ADR-026 a failed republication leaves the Order pending; the marker is best effort")
    void failedRepublicationAndMarkerFailures() {
        Order failedPublication = pendingOrder(CUSTOMER_A, 1, "A-1-1");
        Order failedMarker = pendingOrder(CUSTOMER_B, 2, "A-1-2");
        fixture.clock.advance(Duration.ofMinutes(1));
        fixture.orderPublisher.script(PublishResult.FAILED);
        fixture.store.failMarkEnqueued(true);

        CycleResult result = fixture.sweep();

        assertThat(result.count(ItemOutcome.FAILED)).isEqualTo(1);
        assertThat(result.count(ItemOutcome.REPUBLISHED)).isEqualTo(1);
        assertThat(fixture.order(failedPublication.orderId()).enqueued()).isFalse();
        assertThat(fixture.order(failedMarker.orderId()).enqueued()).isFalse();
    }

    @Test
    @DisplayName("ADR-025 ADR-026 quarantined, terminal and already enqueued Orders are never republished")
    void ineligibleOrdersAreSkipped() {
        int published = fixture.orderPublisher.published().size();
        Order quarantined = pendingOrder(CUSTOMER_A, 1, "A-1-1");
        fixture.clock.advance(Duration.ofMinutes(1));
        OrderRecord stored = fixture.order(quarantined.orderId());
        fixture.store.beforeNext(Operation.FIND_ORDER, () -> fixture.store.putOrder(
                new OrderRecord(stored.order().quarantine(NOW, "review"), NOW, NOW, null)));

        assertThat(fixture.sweep().count(ItemOutcome.NOT_APPLICABLE)).isEqualTo(1);

        Order terminal = pendingOrder(CUSTOMER_B, 2, "A-1-2");
        fixture.clock.advance(Duration.ofMinutes(1));
        fixture.store.beforeNext(Operation.FIND_ORDER, () -> fixture.store.putOrder(
                new OrderRecord(terminal.failProcessing(NOW), NOW, NOW, null)));

        assertThat(fixture.sweep().count(ItemOutcome.NOT_APPLICABLE)).isEqualTo(1);

        Order enqueued = pendingOrder("customer-c", 3, "A-1-3");
        fixture.clock.advance(Duration.ofMinutes(1));
        OrderRecord enqueuedRecord = fixture.order(enqueued.orderId());
        fixture.store.beforeNext(Operation.FIND_ORDER, () -> fixture.store.putOrder(new OrderRecord(
                enqueuedRecord.order(), enqueuedRecord.createdAt(), enqueuedRecord.updatedAt(), NOW)));

        assertThat(fixture.sweep().count(ItemOutcome.NOT_APPLICABLE)).isEqualTo(1);
        assertThat(fixture.orderPublisher.published()).hasSize(published + 3);
    }
}
