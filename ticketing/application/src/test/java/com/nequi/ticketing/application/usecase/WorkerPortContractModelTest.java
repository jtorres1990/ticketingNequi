package com.nequi.ticketing.application.usecase;

import static com.nequi.ticketing.application.usecase.WorkerFixture.CUSTOMER_A;
import static com.nequi.ticketing.application.usecase.WorkerFixture.EVENT_ID;
import static com.nequi.ticketing.application.usecase.WorkerFixture.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nequi.ticketing.application.port.in.CycleRequest;
import com.nequi.ticketing.application.port.in.CycleResult;
import com.nequi.ticketing.application.port.in.Delivery;
import com.nequi.ticketing.application.port.in.DispositionReason;
import com.nequi.ticketing.application.port.in.ItemOutcome;
import com.nequi.ticketing.application.port.in.MessageDisposition;
import com.nequi.ticketing.application.port.in.ProcessOrderCommand;
import com.nequi.ticketing.application.port.in.ProvisionEventCommand;
import com.nequi.ticketing.application.port.in.ProvisioningProgressListener;
import com.nequi.ticketing.application.port.out.AuthorizationOutcome;
import com.nequi.ticketing.application.port.out.CancellationOutcome;
import com.nequi.ticketing.application.port.out.ClosurePlan;
import com.nequi.ticketing.application.port.out.ConfirmationPlan;
import com.nequi.ticketing.application.port.out.EnablementPlan;
import com.nequi.ticketing.application.port.out.InventoryVerification;
import com.nequi.ticketing.application.port.out.LateApprovalPlan;
import com.nequi.ticketing.application.port.out.PaymentAuthorization;
import com.nequi.ticketing.application.port.out.PaymentLease;
import com.nequi.ticketing.application.port.out.PaymentStartPlan;
import com.nequi.ticketing.application.port.out.ProvisioningFailurePlan;
import com.nequi.ticketing.application.port.out.ProvisioningSnapshot;
import com.nequi.ticketing.application.port.out.ReversalCompletionPlan;
import com.nequi.ticketing.application.port.out.ReversalExhaustionPlan;
import com.nequi.ticketing.application.port.out.StalledProvisioning;
import com.nequi.ticketing.domain.audit.AuditRecord;
import com.nequi.ticketing.domain.audit.AuditRecord.Actor;
import com.nequi.ticketing.domain.audit.AuditRecord.ActorType;
import com.nequi.ticketing.domain.audit.AuditRecord.AuditCode;
import com.nequi.ticketing.domain.audit.AuditRecordBuilder;
import com.nequi.ticketing.domain.event.Event;
import com.nequi.ticketing.domain.event.InventoryLimits;
import com.nequi.ticketing.domain.order.Order;
import com.nequi.ticketing.domain.order.Order.PaymentOutcome;
import com.nequi.ticketing.domain.order.PurchaseRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WorkerPortContractModelTest {

    private static final com.nequi.ticketing.domain.order.OrderRules RULES =
            com.nequi.ticketing.domain.order.OrderRules.DEPLOYED;
    private static final com.nequi.ticketing.domain.order.ReversalSchedule SCHEDULE =
            com.nequi.ticketing.domain.order.ReversalSchedule.DEPLOYED;
    private static final com.nequi.ticketing.domain.event.ShardingPolicy SHARDING =
            com.nequi.ticketing.domain.event.ShardingPolicy.DEPLOYED;

    private static final Order ORDER = Order.create("order-1", CUSTOMER_A,
            new PurchaseRequest(EVENT_ID, List.of("A-1-1"), ApiFixture.key(1)), NOW);
    private static final Order OTHER = Order.create("order-2", CUSTOMER_A,
            new PurchaseRequest(EVENT_ID, List.of("A-1-1"), ApiFixture.key(2)), NOW);
    private static final AuditRecord AUDIT = new AuditRecordBuilder().code(AuditCode.PAYMENT_STARTED).order("order-1")
            .actor(new Actor(ActorType.WORKER, "w")).correlation("c").occurredAt(NOW).build();
    private static final PaymentLease LEASE = new PaymentLease("w/1", NOW.plusSeconds(45));

    @Test
    @DisplayName("ADR-025 AP-012 AP-014 AP-015 the Order transition plans describe one transition of the same Order")
    void orderTransitionPlansAreConsistent() {
        Order started = ORDER.startPayment(NOW);
        Order confirmed = started.recordPaymentOutcome(PaymentOutcome.APPROVED).confirm(NOW);
        Order rejected = started.recordPaymentOutcome(PaymentOutcome.DECLINED).reject();

        assertThat(new PaymentStartPlan(ORDER, started, LEASE, AUDIT, NOW, NOW.plusSeconds(15)).lease()).isEqualTo(LEASE);
        assertThatThrownBy(() -> new PaymentStartPlan(OTHER, started, LEASE, AUDIT, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentStartPlan(started, started, LEASE, AUDIT, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentStartPlan(ORDER, ORDER, LEASE, AUDIT, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(new ConfirmationPlan(started, confirmed, "ref", AUDIT, NOW).providerReference()).isEqualTo("ref");
        assertThatThrownBy(() -> new ConfirmationPlan(ORDER, confirmed, null, AUDIT, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConfirmationPlan(started, rejected, null, AUDIT, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConfirmationPlan(OTHER.startPayment(NOW), confirmed, null, AUDIT, NOW))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(new ClosurePlan(ClosurePlan.Kind.REJECT, started, rejected, null, AUDIT, NOW).kind().target())
                .isEqualTo(Order.OrderStatus.REJECTED);
        assertThatThrownBy(() -> new ClosurePlan(ClosurePlan.Kind.EXPIRE, started, rejected, null, AUDIT, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClosurePlan(ClosurePlan.Kind.REJECT, OTHER, rejected, null, AUDIT, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ADR-008 ADR-025 AP-030 AP-032 the late approval and reversal plans only apply to Orders closed without confirmation")
    void reversalPlansAreConsistent() {
        Order started = ORDER.startPayment(NOW);
        Order failed = started.failProcessing(NOW);
        Order rejected = started.recordPaymentOutcome(PaymentOutcome.DECLINED).reject();
        Order confirmed = started.recordPaymentOutcome(PaymentOutcome.APPROVED).confirm(NOW);

        assertThat(new LateApprovalPlan(rejected, rejected.recordLateApproval(NOW), AUDIT).updated().reversalPending())
                .isTrue();
        assertThatThrownBy(() -> new LateApprovalPlan(ORDER, ORDER, AUDIT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LateApprovalPlan(confirmed, confirmed, AUDIT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LateApprovalPlan(ORDER.failEnqueue(), ORDER.failEnqueue(), AUDIT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LateApprovalPlan(rejected, failed, AUDIT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LateApprovalPlan(rejected, OTHER, AUDIT)).isInstanceOf(IllegalArgumentException.class);

        Order completed = failed.completeReversal(NOW);
        assertThat(new ReversalCompletionPlan(failed, completed, AUDIT).completed()).isEqualTo(completed);
        assertThatThrownBy(() -> new ReversalCompletionPlan(completed, completed, AUDIT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReversalCompletionPlan(failed, failed, AUDIT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReversalCompletionPlan(failed, OTHER, AUDIT))
                .isInstanceOf(IllegalArgumentException.class);

        Order exhausted = failed;
        for (int attempt = 0; attempt < 10; attempt++) {
            exhausted = exhausted.rescheduleReversal(NOW);
        }
        assertThat(new ReversalExhaustionPlan(failed, exhausted, AUDIT).exhausted()).isEqualTo(exhausted);
        Order exhaustedOrder = exhausted;
        assertThatThrownBy(() -> new ReversalExhaustionPlan(failed, failed, AUDIT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReversalExhaustionPlan(completed, exhaustedOrder, AUDIT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReversalExhaustionPlan(OTHER, exhaustedOrder, AUDIT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReversalExhaustionPlan(failed, ORDER, AUDIT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ADR-024 AP-003 AP-026 the Event plans and the provisioning snapshot describe the same Event")
    void eventPlansAreConsistent() {
        Event event = Event.create("event-1", "E", "V", NOW.plus(Duration.ofDays(3)), 25, ApiFixture.DEFINITION, NOW,
                InventoryLimits.DEPLOYED);
        Event other = Event.create("event-2", "E", "V", NOW.plus(Duration.ofDays(3)), 25, ApiFixture.DEFINITION, NOW,
                InventoryLimits.DEPLOYED);

        assertThat(new EnablementPlan(event, event.enable(25), "w/1", AUDIT, NOW).leaseOwner()).isEqualTo("w/1");
        assertThatThrownBy(() -> new EnablementPlan(event, event.fail(), "w/1", AUDIT, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EnablementPlan(event, other.enable(25), "w/1", AUDIT, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new ProvisioningFailurePlan(event, event.fail(), AUDIT, NOW).failedAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> new ProvisioningFailurePlan(event, event.enable(25), AUDIT, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProvisioningFailurePlan(event, other.fail(), AUDIT, NOW))
                .isInstanceOf(IllegalArgumentException.class);

        ProvisioningSnapshot leased = new ProvisioningSnapshot(event, NOW, 0, null, null, "w/1", NOW.plusSeconds(60),
                null, 0, null);
        assertThat(leased.progressReference()).isEqualTo(NOW);
        assertThat(leased.leaseHeldByOtherAt("w/2", NOW.plusSeconds(60))).isTrue();
        assertThat(leased.leaseHeldByOtherAt("w/2", NOW.plusSeconds(61))).isFalse();
        assertThat(leased.leaseHeldByOtherAt("w/1", NOW)).isFalse();
        assertThat(new ProvisioningSnapshot(event, NOW, 0, null, null).leaseHeldByOtherAt("w/2", NOW)).isFalse();
        assertThatThrownBy(() -> new ProvisioningSnapshot(event, NOW, 0, null, null, "w/1", null, null, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProvisioningSnapshot(event, NOW, 0, null, null, null, null, null, -1, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new StalledProvisioning("event-1", NOW, 2).republishCount()).isEqualTo(2);
        assertThat(new InventoryVerification(25, List.of()).complete()).isTrue();
        assertThatThrownBy(() -> new InventoryVerification(-1, List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ADR-029 ADR-030 commands, deliveries, dispositions and payment results are well formed")
    void inboundModelIsWellFormed() {
        Delivery delivery = new Delivery(5, true);
        assertThatThrownBy(() -> new Delivery(0, false)).isInstanceOf(IllegalArgumentException.class);
        assertThat(ProcessOrderCommand.readable("order-1", "c", delivery).readable()).isTrue();
        assertThat(ProcessOrderCommand.unreadable("bad", delivery).readable()).isFalse();
        assertThatThrownBy(() -> new ProcessOrderCommand("order-1", "c", delivery, "bad"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProcessOrderCommand(null, null, delivery, null))
                .isInstanceOf(IllegalArgumentException.class);
        ProvisionEventCommand provisioning = ProvisionEventCommand.readable("event-1", "c", delivery, null);
        assertThat(provisioning.progress()).isSameAs(ProvisioningProgressListener.NONE);
        provisioning.progress().batchWritten(1);
        assertThat(ProvisionEventCommand.unreadable("bad", delivery).readable()).isFalse();
        assertThatThrownBy(() -> new ProvisionEventCommand("event-1", "c", delivery, "bad", null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(MessageDisposition.postponeUntil(NOW, DispositionReason.LEASE_HELD_ELSEWHERE))
                .isInstanceOf(MessageDisposition.PostponeUntil.class);
        assertThat(CycleRequest.of("c").shards(3)).containsExactly(0, 1, 2);
        CycleResult result = new CycleResult(false, Map.of(ItemOutcome.EXPIRED, 2L, ItemOutcome.FAILED, 0L));
        assertThat(result.outcomes()).containsOnlyKeys(ItemOutcome.EXPIRED);
        assertThat(result.total()).isEqualTo(2);
        assertThat(CycleResult.skippedCycle().skipped()).isTrue();

        assertThat(new PaymentAuthorization("a", "o", "e", "c", List.of("A-1-1")).ticketIds()).containsExactly("A-1-1");
        assertThat(LEASE.inForceAt(NOW.plusSeconds(45))).isTrue();
        assertThat(LEASE.inForceAt(NOW.plusSeconds(46))).isFalse();
        assertThat(new AuthorizationOutcome.ContractError(401).status()).isEqualTo(401);
        assertThat(new CancellationOutcome.Cancelled(CancellationOutcome.CancellationStatus.VOIDED).status())
                .isEqualTo(CancellationOutcome.CancellationStatus.VOIDED);
    }

    @Test
    @DisplayName("ADR-029 ADR-024 ADR-028 IV-004 deployed worker settings carry the approved defaults and reject invalid values")
    void workerSettings() {
        WorkerUseCaseSettings settings = WorkerUseCaseSettings.deployed("worker-1");

        assertThat(settings.paymentLeaseDuration()).isEqualTo(Duration.ofSeconds(45));
        assertThat(settings.authorizationMargin()).isEqualTo(Duration.ofSeconds(2));
        assertThat(settings.provisioningLeaseDuration()).isEqualTo(Duration.ofSeconds(60));
        assertThat(settings.inventoryLimits().provisioningBatchSize()).isEqualTo(100);
        assertThat(settings.stalledProvisioningThreshold()).isEqualTo(Duration.ofMinutes(3));
        assertThat(settings.maximumProvisioningRepublications()).isEqualTo(3);
        assertThat(settings.republishAge()).isEqualTo(Duration.ofSeconds(30));
        assertThat(List.of(settings.expirationConcurrency(), settings.republishConcurrency(),
                settings.reversalConcurrency(), settings.cleanupConcurrency())).containsExactly(16, 8, 4, 2);
        assertThatThrownBy(() -> WorkerUseCaseSettings.deployed(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkerUseCaseSettings("w", Duration.ZERO, Duration.ofSeconds(2),
                Duration.ofSeconds(60), InventoryLimits.DEPLOYED, Duration.ofMinutes(3), 3, Duration.ofSeconds(30),
                16, 8, 4, 2, 5, RULES, SCHEDULE, 3, SHARDING)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkerUseCaseSettings("w", Duration.ofSeconds(45), Duration.ofSeconds(2),
                Duration.ofSeconds(60), InventoryLimits.DEPLOYED, Duration.ofMinutes(3), 3, Duration.ofSeconds(30),
                0, 8, 4, 2, 5, RULES, SCHEDULE, 3, SHARDING)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkerUseCaseSettings("w", Duration.ofSeconds(45), Duration.ofSeconds(2),
                Duration.ofSeconds(60), InventoryLimits.DEPLOYED, Duration.ofMinutes(3), -1, Duration.ofSeconds(30),
                16, 8, 4, 2, 5, RULES, SCHEDULE, 3, SHARDING)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkerUseCaseSettings("w", Duration.ofSeconds(45), Duration.ofSeconds(2),
                Duration.ofSeconds(60), InventoryLimits.DEPLOYED, Duration.ofMinutes(3), 3, Duration.ofSeconds(30),
                16, 8, 4, 2, 5, RULES, SCHEDULE, -1, SHARDING)).isInstanceOf(IllegalArgumentException.class);
        assertThat(settings.orderRules()).isEqualTo(RULES);
        assertThat(settings.reversalSchedule()).isEqualTo(SCHEDULE);
        assertThat(settings.maximumVerificationRepairs()).isEqualTo(3);
        assertThat(settings.sharding()).isEqualTo(SHARDING);
    }
}
