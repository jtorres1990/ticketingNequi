package com.nequi.ticketing.application.usecase;

import static com.nequi.ticketing.application.usecase.ApiFixture.CORRELATION;
import static com.nequi.ticketing.application.usecase.ApiFixture.CUSTOMER_A;
import static com.nequi.ticketing.application.usecase.ApiFixture.DEFINITION;
import static com.nequi.ticketing.application.usecase.ApiFixture.EVENT_ID;
import static com.nequi.ticketing.application.usecase.ApiFixture.NOW;
import static com.nequi.ticketing.application.usecase.ApiFixture.key;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nequi.ticketing.application.error.RequestRejectedException;
import com.nequi.ticketing.application.port.out.EnqueueFailurePlan;
import com.nequi.ticketing.application.port.out.FailedItem;
import com.nequi.ticketing.application.port.out.IdempotencyRecord;
import com.nequi.ticketing.application.port.out.ItemFailure;
import com.nequi.ticketing.application.port.out.NewEventPlan;
import com.nequi.ticketing.application.port.out.OrderRecord;
import com.nequi.ticketing.application.port.out.ProvisioningSnapshot;
import com.nequi.ticketing.application.port.out.PublisherAvailability;
import com.nequi.ticketing.application.port.out.QuarantinePlan;
import com.nequi.ticketing.application.port.out.ReservationPlan;
import com.nequi.ticketing.application.port.out.TransactionOutcome;
import com.nequi.ticketing.domain.audit.AuditRecord;
import com.nequi.ticketing.domain.error.DomainErrorCode;
import com.nequi.ticketing.domain.event.Event;
import com.nequi.ticketing.domain.event.InventoryLimits;
import com.nequi.ticketing.domain.order.ActiveOrderKey;
import com.nequi.ticketing.domain.order.Order;
import com.nequi.ticketing.domain.order.PurchaseRequest;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PortContractModelTest {

    private static final Order ORDER = Order.create("order-1", CUSTOMER_A,
            new PurchaseRequest(EVENT_ID, List.of("A-1-1"), key(1)), NOW);
    private static final IdempotencyRecord IDEMPOTENCY = new IdempotencyRecord(
            CUSTOMER_A, key(1), "order-1", "hash", NOW, NOW.plus(Duration.ofHours(24)));
    private static final AuditRecord AUDIT = ApiAudits.reservationCreated(ORDER, CORRELATION);

    @Test
    @DisplayName("ADR-039 a cancellation reports the failed items, with ticket identifiers only for Ticket items")
    void cancellationReportsFailedItems() {
        TransactionOutcome.Cancelled cancelled = new TransactionOutcome.Cancelled(List.of(
                ItemFailure.ticket(FailedItem.TICKET_STATE, "A-1-2"),
                ItemFailure.ticket(FailedItem.TICKET_STATE, "A-1-1"),
                ItemFailure.ticket(FailedItem.TICKET_STATE, "A-1-1"),
                ItemFailure.of(FailedItem.ACTIVE_ORDER_LOCK)));

        assertThat(cancelled.failed(FailedItem.ACTIVE_ORDER_LOCK)).isTrue();
        assertThat(cancelled.failed(FailedItem.TICKET_MISSING)).isFalse();
        assertThat(cancelled.ticketIds(FailedItem.TICKET_STATE)).containsExactly("A-1-1", "A-1-2");
        assertThatThrownBy(() -> TransactionOutcome.cancelled(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ItemFailure(FailedItem.TICKET_STATE, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ItemFailure(FailedItem.ORDER, "A-1-1")).isInstanceOf(IllegalArgumentException.class);
        assertThat(TransactionOutcome.applied()).isInstanceOf(TransactionOutcome.Applied.class);
    }

    @Test
    @DisplayName("ADR-023 the reservation plan describes a single new Order with its own idempotency record and lock")
    void reservationPlanIsConsistent() {
        assertThat(new ReservationPlan(ORDER, IDEMPOTENCY, AUDIT, ActiveOrderKey.of(ORDER)).order()).isEqualTo(ORDER);
        IdempotencyRecord otherOrder = new IdempotencyRecord(CUSTOMER_A, key(1), "order-2", "hash", NOW, NOW);
        assertThatThrownBy(() -> new ReservationPlan(ORDER, otherOrder, AUDIT, ActiveOrderKey.of(ORDER)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReservationPlan(ORDER, IDEMPOTENCY, AUDIT, new ActiveOrderKey("other", EVENT_ID)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReservationPlan(ORDER.failEnqueue(), IDEMPOTENCY, AUDIT, ActiveOrderKey.of(ORDER)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ADR-026 ADR-025 enqueue failure and quarantine plans refer to the same Order")
    void transitionPlansReferToTheSameOrder() {
        Order other = Order.create("order-2", CUSTOMER_A, new PurchaseRequest(EVENT_ID, List.of("A-1-1"), key(1)), NOW);
        assertThat(new EnqueueFailurePlan(ORDER, ORDER.failEnqueue(), AUDIT, NOW).failedAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> new EnqueueFailurePlan(ORDER, other.failEnqueue(), AUDIT, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EnqueueFailurePlan(ORDER, ORDER, AUDIT, NOW)).isInstanceOf(IllegalArgumentException.class);
        Order quarantined = ORDER.quarantine(NOW, "reason");
        assertThat(new QuarantinePlan(ORDER, quarantined, AUDIT).quarantined()).isEqualTo(quarantined);
        assertThatThrownBy(() -> new QuarantinePlan(ORDER, ORDER, AUDIT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuarantinePlan(ORDER, other.quarantine(NOW, "reason"), AUDIT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ADR-024 the new Event plan starts in PROVISIONING with inventory counts matching the capacity")
    void newEventPlanIsConsistent() {
        Event event = Event.create("e-1", "N", "V", NOW.plus(Duration.ofDays(1)), 25, DEFINITION, NOW, InventoryLimits.DEPLOYED);
        AuditRecord audit = ApiAudits.eventProvisioningRequested(event, "admin", CORRELATION, NOW);
        assertThat(new NewEventPlan(event, IDEMPOTENCY, audit, NOW, "admin", 24, 1, 1).totalBatches()).isEqualTo(1);
        assertThatThrownBy(() -> new NewEventPlan(event, IDEMPOTENCY, audit, NOW, "admin", 25, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NewEventPlan(event, IDEMPOTENCY, audit, NOW, "admin", 24, 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NewEventPlan(event.enable(25), IDEMPOTENCY, audit, NOW, "admin", 24, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProvisioningSnapshot(event, NOW, -1, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ADR-035 publisher availability carries a non-negative retry delay and no circuit breaker type")
    void publisherAvailability() {
        assertThat(PublisherAvailability.available()).isInstanceOf(PublisherAvailability.Available.class);
        assertThat(PublisherAvailability.unavailable(Duration.ofSeconds(2)))
                .isEqualTo(new PublisherAvailability.Unavailable(Duration.ofSeconds(2)));
        assertThatThrownBy(() -> PublisherAvailability.unavailable(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ADR-027 idempotency records compare request content by hash; ADR-026 the enqueue marker is technical")
    void idempotencyAndOrderRecords() {
        assertThat(IDEMPOTENCY.sameContent("hash")).isTrue();
        assertThat(IDEMPOTENCY.sameContent("other")).isFalse();
        assertThat(new OrderRecord(ORDER, NOW, NOW, null).enqueued()).isFalse();
        assertThat(new OrderRecord(ORDER, NOW, NOW, NOW).enqueued()).isTrue();
    }

    @Test
    @DisplayName("ADR-035 every use case rejection carries a stable code and no technical detail")
    void rejectionsCarryStableCodes() {
        assertThat(RequestRejectedException.eventNotFound().code()).isEqualTo(DomainErrorCode.EVENT_NOT_FOUND);
        assertThat(RequestRejectedException.orderNotFound().code()).isEqualTo(DomainErrorCode.ORDER_NOT_FOUND);
        assertThat(RequestRejectedException.eventNotOnSale().code()).isEqualTo(DomainErrorCode.EVENT_NOT_ON_SALE);
        assertThat(RequestRejectedException.activeOrderExists().code()).isEqualTo(DomainErrorCode.ACTIVE_ORDER_EXISTS);
        assertThat(RequestRejectedException.idempotencyKeyReused().code()).isEqualTo(DomainErrorCode.IDEMPOTENCY_KEY_REUSED);
        RequestRejectedException unavailable = RequestRejectedException.ticketsUnavailable(List.of("A-1-1"));
        assertThat(unavailable.ticketIds()).containsExactly("A-1-1");
        assertThat(unavailable.retryAfter()).isEmpty();
        assertThat(unavailable.getMessage()).isEqualTo("TICKETS_UNAVAILABLE");
        assertThat(unavailable.getStackTrace()).isEmpty();
        assertThat(RequestRejectedException.serviceUnavailable(Duration.ofSeconds(3)).retryAfter())
                .contains(Duration.ofSeconds(3));
    }

    @Test
    @DisplayName("IV-004 the deployed settings hold the approved values and reject inconsistent configuration")
    void settingsHoldApprovedValues() {
        ApiUseCaseSettings deployed = ApiUseCaseSettings.DEPLOYED;
        assertThat(deployed.inventoryLimits()).isEqualTo(InventoryLimits.DEPLOYED);
        assertThat(deployed.idempotencyRetention()).isEqualTo(Duration.ofHours(24));
        assertThat(deployed.conflictRetryAfter()).isEqualTo(Duration.ofSeconds(1));
        assertThat(deployed.doubleFailureRetryAfter()).isEqualTo(Duration.ofSeconds(1));
        assertThat(deployed.minimumRetryAfter()).isEqualTo(Duration.ofSeconds(1));
        assertThat(deployed.defaultEventPageSize()).isEqualTo(20);
        assertThat(deployed.maximumEventPageSize()).isEqualTo(100);
        assertThat(deployed.defaultAvailabilityPageSize()).isEqualTo(50);
        assertThat(deployed.maximumAvailabilityPageSize()).isEqualTo(100);
        assertThat(deployed.availableCountCacheTtl()).isEqualTo(Duration.ofSeconds(1));
        assertThat(deployed.soldOutProbeConcurrency()).isEqualTo(8);
        assertThatThrownBy(() -> settings(Duration.ZERO, 20, 8)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings(Duration.ofSeconds(-1), 20, 8)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings(Duration.ofSeconds(1), 101, 8)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings(Duration.ofSeconds(1), 20, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    private static ApiUseCaseSettings settings(Duration cacheTtl, int defaultEventPageSize, int probeConcurrency) {
        return new ApiUseCaseSettings(InventoryLimits.DEPLOYED, Duration.ofHours(24), Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ofSeconds(1), defaultEventPageSize, 100, 50, 100, cacheTtl, probeConcurrency,
                com.nequi.ticketing.domain.order.OrderRules.DEPLOYED, com.nequi.ticketing.domain.event.ShardingPolicy.DEPLOYED);
    }
}
