package com.nequi.ticketing.infrastructure.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nequi.ticketing.application.port.out.AvailableTicketPage;
import com.nequi.ticketing.application.port.out.ClosurePlan;
import com.nequi.ticketing.application.port.out.ConfirmationPlan;
import com.nequi.ticketing.application.port.out.EnabledEventPage;
import com.nequi.ticketing.application.port.out.EnablementPlan;
import com.nequi.ticketing.application.port.out.EnqueueFailurePlan;
import com.nequi.ticketing.application.port.out.EventCatalog;
import com.nequi.ticketing.application.port.out.FailedItem;
import com.nequi.ticketing.application.port.out.InventoryVerification;
import com.nequi.ticketing.application.port.out.ItemFailure;
import com.nequi.ticketing.application.port.out.LateApprovalPlan;
import com.nequi.ticketing.application.port.out.NewEventPlan;
import com.nequi.ticketing.application.port.out.OrderLifecycleStore;
import com.nequi.ticketing.application.port.out.PaymentLease;
import com.nequi.ticketing.application.port.out.PaymentStartPlan;
import com.nequi.ticketing.application.port.out.ProvisioningFailurePlan;
import com.nequi.ticketing.application.port.out.ProvisioningSnapshot;
import com.nequi.ticketing.application.port.out.QuarantinePlan;
import com.nequi.ticketing.application.port.out.ReservationPlan;
import com.nequi.ticketing.application.port.out.ReversalCompletionPlan;
import com.nequi.ticketing.application.port.out.ReversalExhaustionPlan;
import com.nequi.ticketing.application.port.out.StalledProvisioning;
import com.nequi.ticketing.application.port.out.TicketInventory;
import com.nequi.ticketing.application.port.out.TransactionOutcome;
import com.nequi.ticketing.domain.audit.AuditRecord;
import com.nequi.ticketing.domain.event.Event;
import com.nequi.ticketing.domain.order.Order;
import com.nequi.ticketing.domain.order.Order.PaymentOutcome;
import com.nequi.ticketing.domain.order.PurchaseRequest;
import com.nequi.ticketing.domain.order.ReversalPlan;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** CMP-018 business metrics derived from the transitions applied by the outbound ports. */
class ObservedPortsTest {

    private static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");
    private static final TransactionOutcome APPLIED = TransactionOutcome.applied();
    private static final TransactionOutcome CANCELLED =
            TransactionOutcome.cancelled(List.of(new ItemFailure(FailedItem.ORDER, null)));

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final LogDispatcher dispatcher = LogDispatcher.start(1_000);
    private final Telemetry telemetry = new Telemetry(registry, dispatcher, ObservabilitySettings.DEPLOYED, Tracer.NOOP);

    @AfterEach
    void closeDispatcher() {
        dispatcher.close(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("CMP-018 ADR-028 terminal Orders by status and cause, expiration lag and its threshold, expired without payment")
    void closures() {
        OrderLifecycleStore delegate = mock(OrderLifecycleStore.class);
        OrderLifecycleStore observed = telemetry.observeLifecycle(delegate);
        Order created = created("o-1");
        Order expired = created.expire(created.reservation().expiresAt());
        when(delegate.close(any())).thenReturn(Mono.just(APPLIED), Mono.just(APPLIED), Mono.just(CANCELLED),
                Mono.just(APPLIED));

        StepVerifier.create(observed.close(closure(ClosurePlan.Kind.EXPIRE, created, expired,
                created.reservation().expiresAt().plusSeconds(3)))).expectNext(APPLIED).verifyComplete();
        StepVerifier.create(observed.close(closure(ClosurePlan.Kind.EXPIRE, created, expired,
                created.reservation().expiresAt().plusSeconds(40)))).expectNext(APPLIED).verifyComplete();
        StepVerifier.create(observed.close(closure(ClosurePlan.Kind.EXPIRE, created, expired,
                created.reservation().expiresAt()))).expectNext(CANCELLED).verifyComplete();
        Order paid = created.startPayment(NOW);
        Order failed = paid.failProcessing(NOW.plusSeconds(5));
        StepVerifier.create(observed.close(closure(ClosurePlan.Kind.FAIL_PROCESSING, paid, failed, NOW.plusSeconds(5))))
                .expectNext(APPLIED).verifyComplete();

        assertThat(count(MetricNames.ORDERS_TERMINAL, "status", "EXPIRED", "cause", "RESERVATION_EXPIRED")).isEqualTo(2);
        assertThat(count(MetricNames.ORDERS_TERMINAL, "status", "FAILED", "cause", "PROCESSING_FAILED")).isEqualTo(1);
        assertThat(count(MetricNames.PAYMENT_REVERSALS, "outcome", "requested")).isEqualTo(1);
        assertThat(registry.get(MetricNames.EXPIRATION_LAG).timer().count()).isEqualTo(2);
        assertThat(count(MetricNames.EXPIRATION_LAG_EXCEEDED)).isEqualTo(1);
        assertThat(count(MetricNames.EXPIRED_WITHOUT_PAYMENT)).isEqualTo(2);
        StepVerifier.create(Mono.just(1)).expectNext(1).verifyComplete();
    }

    @Test
    @DisplayName("CMP-018 ADR-025 confirmations, enqueue failures, quarantines, late approvals and reversals")
    void otherTransitions() {
        OrderLifecycleStore delegate = mock(OrderLifecycleStore.class);
        OrderLifecycleStore observed = telemetry.observeLifecycle(delegate);
        Order created = created("o-2");
        Order approved = created.startPayment(NOW).recordPaymentOutcome(PaymentOutcome.APPROVED);
        Order confirmed = approved.confirm(NOW.plusSeconds(1));
        ConfirmationPlan confirmation = mock(ConfirmationPlan.class);
        when(confirmation.confirmed()).thenReturn(confirmed);
        when(delegate.confirm(any())).thenReturn(Mono.just(APPLIED), Mono.just(CANCELLED));
        StepVerifier.create(observed.confirm(confirmation)).expectNext(APPLIED).verifyComplete();
        StepVerifier.create(observed.confirm(confirmation)).expectNext(CANCELLED).verifyComplete();

        EnqueueFailurePlan enqueueFailure = mock(EnqueueFailurePlan.class);
        when(enqueueFailure.failed()).thenReturn(created.failEnqueue());
        when(delegate.failEnqueue(any())).thenReturn(Mono.just(APPLIED), Mono.just(CANCELLED));
        StepVerifier.create(observed.failEnqueue(enqueueFailure)).expectNext(APPLIED).verifyComplete();
        StepVerifier.create(observed.failEnqueue(enqueueFailure)).expectNext(CANCELLED).verifyComplete();

        QuarantinePlan quarantine = mock(QuarantinePlan.class);
        AuditRecord audit = mock(AuditRecord.class);
        when(audit.correlationId()).thenReturn("corr");
        when(quarantine.quarantined()).thenReturn(created.quarantine(NOW, "TICKET_CONDITION_FAILED"));
        when(quarantine.audit()).thenReturn(audit);
        when(delegate.quarantine(any())).thenReturn(Mono.just(APPLIED), Mono.just(CANCELLED));
        StepVerifier.create(observed.quarantine(quarantine)).expectNext(APPLIED).verifyComplete();
        StepVerifier.create(observed.quarantine(quarantine)).expectNext(CANCELLED).verifyComplete();

        Order rejected = created.startPayment(NOW).recordPaymentOutcome(PaymentOutcome.DECLINED).reject();
        LateApprovalPlan marks = mock(LateApprovalPlan.class);
        when(marks.current()).thenReturn(rejected);
        when(marks.updated()).thenReturn(rejected.recordLateApproval(NOW.plusSeconds(2)));
        LateApprovalPlan alreadyMarked = mock(LateApprovalPlan.class);
        Order failed = created.startPayment(NOW).failProcessing(NOW.plusSeconds(1));
        when(alreadyMarked.current()).thenReturn(failed);
        when(alreadyMarked.updated()).thenReturn(failed);
        when(delegate.recordLateApproval(any())).thenReturn(Mono.just(APPLIED), Mono.just(APPLIED), Mono.just(CANCELLED));
        StepVerifier.create(observed.recordLateApproval(marks)).expectNext(APPLIED).verifyComplete();
        StepVerifier.create(observed.recordLateApproval(alreadyMarked)).expectNext(APPLIED).verifyComplete();
        StepVerifier.create(observed.recordLateApproval(marks)).expectNext(CANCELLED).verifyComplete();

        ReversalCompletionPlan completion = mock(ReversalCompletionPlan.class);
        when(completion.completed()).thenReturn(failed.completeReversal(NOW.plusSeconds(3)));
        when(delegate.completeReversal(any())).thenReturn(Mono.just(APPLIED), Mono.just(CANCELLED));
        StepVerifier.create(observed.completeReversal(completion)).expectNext(APPLIED).verifyComplete();
        StepVerifier.create(observed.completeReversal(completion)).expectNext(CANCELLED).verifyComplete();

        Order exhausted = failed;
        for (int attempt = 0; attempt < ReversalPlan.MAXIMUM_ATTEMPTS; attempt++) {
            exhausted = exhausted.rescheduleReversal(NOW.plusSeconds(10 + attempt));
        }
        ReversalExhaustionPlan exhaustion = mock(ReversalExhaustionPlan.class);
        when(exhaustion.exhausted()).thenReturn(exhausted);
        when(delegate.exhaustReversal(any())).thenReturn(Mono.just(APPLIED), Mono.just(CANCELLED));
        StepVerifier.create(observed.exhaustReversal(exhaustion)).expectNext(APPLIED).verifyComplete();
        StepVerifier.create(observed.exhaustReversal(exhaustion)).expectNext(CANCELLED).verifyComplete();

        when(delegate.rescheduleReversal(anyString(), anyInt(), any())).thenReturn(Mono.just(true), Mono.just(false));
        StepVerifier.create(observed.rescheduleReversal("o-2", 0, failed.reversalPlan())).expectNext(true).verifyComplete();
        StepVerifier.create(observed.rescheduleReversal("o-2", 0, failed.reversalPlan())).expectNext(false).verifyComplete();

        assertThat(count(MetricNames.ORDERS_TERMINAL, "status", "CONFIRMED", "cause", "none")).isEqualTo(1);
        assertThat(count(MetricNames.ORDERS_TERMINAL, "status", "FAILED", "cause", "PROCESSING_UNAVAILABLE")).isEqualTo(1);
        assertThat(count(MetricNames.ORDERS_QUARANTINED)).isEqualTo(1);
        assertThat(count(MetricNames.LATE_APPROVALS)).isEqualTo(2);
        assertThat(count(MetricNames.PAYMENT_REVERSALS, "outcome", "requested")).isEqualTo(1);
        assertThat(count(MetricNames.PAYMENT_REVERSALS, "outcome", "confirmed")).isEqualTo(1);
        assertThat(count(MetricNames.PAYMENT_REVERSALS, "outcome", "exhausted")).isEqualTo(1);
        assertThat(count(MetricNames.PAYMENT_REVERSALS, "outcome", "rescheduled")).isEqualTo(1);
    }

    @Test
    @DisplayName("CMP-018 the operations without a metric are delegated unchanged")
    void passThroughOperations() {
        OrderLifecycleStore delegate = mock(OrderLifecycleStore.class);
        OrderLifecycleStore observed = telemetry.observeLifecycle(delegate);
        ReservationPlan reservation = mock(ReservationPlan.class);
        PaymentStartPlan start = mock(PaymentStartPlan.class);
        PaymentLease lease = new PaymentLease("w/1", NOW);
        when(delegate.reserve(reservation)).thenReturn(Mono.just(APPLIED));
        when(delegate.markEnqueued("o-1", NOW)).thenReturn(Mono.just(true));
        when(delegate.startPayment(start)).thenReturn(Mono.just(APPLIED));
        when(delegate.claimPaymentLease("o-1", "o-1-1", lease, NOW)).thenReturn(Mono.just(true));
        StepVerifier.create(observed.reserve(reservation)).expectNext(APPLIED).verifyComplete();
        StepVerifier.create(observed.markEnqueued("o-1", NOW)).expectNext(true).verifyComplete();
        StepVerifier.create(observed.startPayment(start)).expectNext(APPLIED).verifyComplete();
        StepVerifier.create(observed.claimPaymentLease("o-1", "o-1-1", lease, NOW)).expectNext(true).verifyComplete();

        EventCatalog catalog = mock(EventCatalog.class);
        EventCatalog observedCatalog = telemetry.observeCatalog(catalog);
        Event event = mock(Event.class);
        when(catalog.findEvent("e-1")).thenReturn(Mono.just(event));
        when(catalog.findProvisioningSnapshot("e-1")).thenReturn(Mono.just(mock(ProvisioningSnapshot.class)));
        when(catalog.listEnabledUpcoming(NOW, 10, null)).thenReturn(Mono.just(mock(EnabledEventPage.class)));
        when(catalog.acquireProvisioningLease("e-1", "w", NOW, NOW)).thenReturn(Mono.just(true));
        when(catalog.recordProvisioningProgress("e-1", "w", NOW, 2, NOW)).thenReturn(Mono.just(true));
        when(catalog.findStalledProvisioning(NOW)).thenReturn(Flux.just(mock(StalledProvisioning.class)));
        when(catalog.findFailedPendingPurge()).thenReturn(Flux.just("e-1"));
        when(catalog.markTicketsPurged("e-1", NOW)).thenReturn(Mono.just(true));
        StepVerifier.create(observedCatalog.findEvent("e-1")).expectNext(event).verifyComplete();
        StepVerifier.create(observedCatalog.findProvisioningSnapshot("e-1")).expectNextCount(1).verifyComplete();
        StepVerifier.create(observedCatalog.listEnabledUpcoming(NOW, 10, null)).expectNextCount(1).verifyComplete();
        StepVerifier.create(observedCatalog.acquireProvisioningLease("e-1", "w", NOW, NOW)).expectNext(true).verifyComplete();
        StepVerifier.create(observedCatalog.recordProvisioningProgress("e-1", "w", NOW, 2, NOW)).expectNext(true)
                .verifyComplete();
        StepVerifier.create(observedCatalog.findStalledProvisioning(NOW)).expectNextCount(1).verifyComplete();
        StepVerifier.create(observedCatalog.findFailedPendingPurge()).expectNext("e-1").verifyComplete();
        StepVerifier.create(observedCatalog.markTicketsPurged("e-1", NOW)).expectNext(true).verifyComplete();

        TicketInventory inventory = mock(TicketInventory.class);
        TicketInventory observedInventory = telemetry.observeInventory(inventory);
        when(inventory.hasAvailable(event)).thenReturn(Mono.just(true));
        when(inventory.countAvailable(event)).thenReturn(Mono.just(7L));
        when(inventory.findAvailablePage(event, "A", 50, null)).thenReturn(Mono.just(mock(AvailableTicketPage.class)));
        when(inventory.writeBatch(event, List.of())).thenReturn(Mono.empty());
        when(inventory.verify(event, List.of())).thenReturn(Mono.just(new InventoryVerification(0, List.of())));
        when(inventory.purge(event, List.of())).thenReturn(Mono.empty());
        StepVerifier.create(observedInventory.hasAvailable(event)).expectNext(true).verifyComplete();
        StepVerifier.create(observedInventory.countAvailable(event)).expectNext(7L).verifyComplete();
        StepVerifier.create(observedInventory.findAvailablePage(event, "A", 50, null)).expectNextCount(1).verifyComplete();
        StepVerifier.create(observedInventory.writeBatch(event, List.of())).verifyComplete();
        StepVerifier.create(observedInventory.verify(event, List.of())).expectNextCount(1).verifyComplete();
        StepVerifier.create(observedInventory.purge(event, List.of())).verifyComplete();
        assertThat(count(MetricNames.AVAILABILITY_COUNT_QUERIES)).isEqualTo(1);
        verify(inventory).countAvailable(event);
    }

    @Test
    @DisplayName("CMP-018 ADR-024 provisioning started, enabled, failed and republished")
    void provisioning() {
        EventCatalog catalog = mock(EventCatalog.class);
        EventCatalog observed = telemetry.observeCatalog(catalog);
        Event event = mock(Event.class);
        when(event.eventId()).thenReturn("e-1");
        when(event.capacity()).thenReturn(25);
        AuditRecord audit = mock(AuditRecord.class);
        NewEventPlan creation = mock(NewEventPlan.class);
        when(creation.event()).thenReturn(event);
        when(creation.audit()).thenReturn(audit);
        EnablementPlan enablement = mock(EnablementPlan.class);
        when(enablement.enabled()).thenReturn(event);
        ProvisioningFailurePlan failure = mock(ProvisioningFailurePlan.class);
        when(failure.failed()).thenReturn(event);
        when(catalog.create(any())).thenReturn(Mono.just(APPLIED), Mono.just(CANCELLED));
        when(catalog.enable(any())).thenReturn(Mono.just(APPLIED), Mono.just(CANCELLED));
        when(catalog.markFailed(any())).thenReturn(Mono.just(APPLIED), Mono.just(CANCELLED));
        when(catalog.registerRepublication(anyString(), any(), any())).thenReturn(Mono.just(true), Mono.just(false));

        for (int call = 0; call < 2; call++) {
            StepVerifier.create(observed.create(creation)).expectNextCount(1).verifyComplete();
            StepVerifier.create(observed.enable(enablement)).expectNextCount(1).verifyComplete();
            StepVerifier.create(observed.markFailed(failure)).expectNextCount(1).verifyComplete();
            StepVerifier.create(observed.registerRepublication("e-1", NOW, NOW)).expectNextCount(1).verifyComplete();
        }

        assertThat(count(MetricNames.PROVISIONING, "outcome", "started")).isEqualTo(1);
        assertThat(count(MetricNames.PROVISIONING, "outcome", "enabled")).isEqualTo(1);
        assertThat(count(MetricNames.PROVISIONING, "outcome", "failed")).isEqualTo(1);
        assertThat(count(MetricNames.PROVISIONING, "outcome", "republished")).isEqualTo(1);
    }

    private static Order created(String orderId) {
        return Order.create(orderId, "customer", new PurchaseRequest("e-1", List.of("A-1-1"), "purchase-key-0000001"),
                NOW);
    }

    private static ClosurePlan closure(ClosurePlan.Kind kind, Order current, Order closed, Instant now) {
        ClosurePlan plan = mock(ClosurePlan.class);
        when(plan.kind()).thenReturn(kind);
        when(plan.current()).thenReturn(current);
        when(plan.closed()).thenReturn(closed);
        when(plan.now()).thenReturn(now);
        return plan;
    }

    private double count(String name, String... tags) {
        return registry.get(name).tags(tags).counter().count();
    }
}
