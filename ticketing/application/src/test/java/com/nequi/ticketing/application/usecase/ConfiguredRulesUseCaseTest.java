package com.nequi.ticketing.application.usecase;

import static com.nequi.ticketing.application.usecase.Rejections.value;
import static com.nequi.ticketing.application.usecase.WorkerFixture.CORRELATION;
import static com.nequi.ticketing.application.usecase.WorkerFixture.CUSTOMER_A;
import static com.nequi.ticketing.application.usecase.WorkerFixture.NOW;
import static com.nequi.ticketing.application.usecase.WorkerFixture.key;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nequi.ticketing.application.error.RequestRejectedException;
import com.nequi.ticketing.application.port.in.CycleRequest;
import com.nequi.ticketing.application.port.in.Delivery;
import com.nequi.ticketing.application.port.in.DispositionReason;
import com.nequi.ticketing.application.port.in.ItemOutcome;
import com.nequi.ticketing.application.port.in.MessageDisposition;
import com.nequi.ticketing.application.port.in.ProcessOrderCommand;
import com.nequi.ticketing.application.port.in.ProvisionEventCommand;
import com.nequi.ticketing.application.port.in.StartPurchaseCommand;
import com.nequi.ticketing.application.port.out.AuthorizationOutcome;
import com.nequi.ticketing.application.port.out.CancellationOutcome;
import com.nequi.ticketing.application.port.out.InventoryVerification;
import com.nequi.ticketing.application.port.out.TicketInventory;
import com.nequi.ticketing.application.testdouble.InMemoryTicketingStore.Operation;
import com.nequi.ticketing.domain.error.DomainErrorCode;
import com.nequi.ticketing.domain.error.ValidationException;
import com.nequi.ticketing.domain.event.Event;
import com.nequi.ticketing.domain.event.ShardingPolicy;
import com.nequi.ticketing.domain.order.Order;
import com.nequi.ticketing.domain.order.Order.OrderStatus;
import com.nequi.ticketing.domain.order.OrderRules;
import com.nequi.ticketing.domain.order.ReversalSchedule;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * IV-012 and IV-015: the use cases apply the configured domain rules carried by {@link ApiUseCaseSettings} and
 * {@link WorkerUseCaseSettings} instead of the approved defaults.
 */
class ConfiguredRulesUseCaseTest {

    @Test
    @DisplayName("IV-012 VAL-012 the purchase applies the configured maximum of Tickets per Order before reserving")
    void purchaseUsesTheConfiguredMaximum() {
        WorkerFixture fixture = new WorkerFixture();
        fixture.seedEvent();
        ApiUseCaseSettings deployed = ApiUseCaseSettings.DEPLOYED;
        ApiUseCaseSettings two = new ApiUseCaseSettings(deployed.inventoryLimits(), deployed.idempotencyRetention(),
                deployed.conflictRetryAfter(), deployed.doubleFailureRetryAfter(), deployed.minimumRetryAfter(),
                deployed.defaultEventPageSize(), deployed.maximumEventPageSize(), deployed.defaultAvailabilityPageSize(),
                deployed.maximumAvailabilityPageSize(), deployed.availableCountCacheTtl(),
                deployed.soldOutProbeConcurrency(), new OrderRules(2, Duration.ofSeconds(15)), deployed.sharding());
        PurchaseService purchases = new PurchaseService(fixture.store, fixture.store, fixture.store, fixture.store,
                fixture.orderPublisher, fixture.clock, fixture.ids, two);

        StepVerifier.create(purchases.startPurchase(new StartPurchaseCommand(CUSTOMER_A, WorkerFixture.EVENT_ID,
                        List.of("A-1-1", "A-1-2", "A-1-3"), key(1), CORRELATION)))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ValidationException.class)
                        .hasMessageContaining("between 1 and 2"))
                .verify();
        assertThat(fixture.store.reservationAttempts()).isZero();
        assertThat(value(purchases.startPurchase(new StartPurchaseCommand(CUSTOMER_A, WorkerFixture.EVENT_ID,
                List.of("A-1-1", "A-1-2"), key(2), CORRELATION))).order().status()).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    @DisplayName("IV-015 ADR-022 the Event creation stores the availability shards of the configured sharding")
    void eventCreationUsesTheConfiguredSharding() {
        ApiFixture api = new ApiFixture();
        ApiUseCaseSettings deployed = ApiUseCaseSettings.DEPLOYED;
        ApiUseCaseSettings sharded = new ApiUseCaseSettings(deployed.inventoryLimits(), deployed.idempotencyRetention(),
                deployed.conflictRetryAfter(), deployed.doubleFailureRetryAfter(), deployed.minimumRetryAfter(),
                deployed.defaultEventPageSize(), deployed.maximumEventPageSize(), deployed.defaultAvailabilityPageSize(),
                deployed.maximumAvailabilityPageSize(), deployed.availableCountCacheTtl(),
                deployed.soldOutProbeConcurrency(), deployed.orderRules(), new ShardingPolicy(10, 32, 8, 4, 8));
        EventManagementService management = new EventManagementService(api.store, api.store, api.provisioningPublisher,
                api.clock, api.ids, sharded);

        String eventId = value(management.createEvent(ApiFixture.createEvent(key(3), NOW.plus(Duration.ofDays(3)), 25,
                ApiFixture.DEFINITION))).status().eventId();

        Event stored = api.store.snapshot(eventId).orElseThrow().event();
        assertThat(stored.availabilityShards()).isEqualTo(3);
    }

    @Test
    @DisplayName("IV-015 BR-029 the Order processing applies the configured payment cutoff margin")
    void processingUsesTheConfiguredCutoff() {
        WorkerFixture fixture = new WorkerFixture();
        fixture.seedEvent();
        Order order = fixture.createOrder(CUSTOMER_A, key(4), "A-1-1");
        fixture.clock.set(order.reservation().expiresAt().minusSeconds(16));
        OrderProcessingService wide = new OrderProcessingService(fixture.store, fixture.store, fixture.gateway,
                fixture.clock, worker(fixture.settings, new OrderRules(10, Duration.ofSeconds(20)),
                        fixture.settings.reversalSchedule(), fixture.settings.maximumVerificationRepairs(),
                        fixture.settings.sharding()));

        MessageDisposition disposition = value(wide.process(ProcessOrderCommand.readable(order.orderId(), CORRELATION,
                new Delivery(1, false))));

        assertThat(disposition).isEqualTo(MessageDisposition.delete(DispositionReason.PAYMENT_CUTOFF));
        assertThat(fixture.order(order.orderId()).order().paymentAttempt()).isNull();
        assertThat(fixture.gateway.authorizations()).isEmpty();
    }

    @Test
    @DisplayName("IV-015 ADR-025 the reversal process follows the configured schedule and exhausts at its maximum of attempts")
    void reversalsUseTheConfiguredSchedule() {
        WorkerFixture fixture = new WorkerFixture();
        fixture.seedEvent();
        Order order = fixture.createOrder(CUSTOMER_A, key(5), "A-1-1");
        fixture.gateway.scriptAuthorizations(new AuthorizationOutcome.DependencyUnavailable("timeout"));
        fixture.process(order.orderId(), 5, true);
        PaymentReversalService reversals = new PaymentReversalService(fixture.store, fixture.store, fixture.gateway,
                fixture.clock, worker(fixture.settings, fixture.settings.orderRules(),
                        new ReversalSchedule(List.of(Duration.ofSeconds(1)), 2),
                        fixture.settings.maximumVerificationRepairs(), fixture.settings.sharding()));

        fixture.gateway.scriptCancellations(new CancellationOutcome.DependencyUnavailable("5xx"));
        assertThat(value(reversals.reverseDue(CycleRequest.of(CORRELATION))).count(ItemOutcome.REVERSAL_RESCHEDULED))
                .isEqualTo(1);
        assertThat(fixture.order(order.orderId()).order().reversalPlan().nextAttemptAt())
                .isEqualTo(fixture.clock.now().plusSeconds(1));
        fixture.clock.advance(Duration.ofSeconds(1));
        fixture.gateway.scriptCancellations(new CancellationOutcome.DependencyUnavailable("5xx"));
        assertThat(value(reversals.reverseDue(CycleRequest.of(CORRELATION))).count(ItemOutcome.REVERSAL_EXHAUSTED))
                .isEqualTo(1);
        assertThat(fixture.order(order.orderId()).order().reversalPlan().attempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("IV-015 ADR-022 the periodic processes visit the configured number of shards")
    void expirationUsesTheConfiguredShards() {
        WorkerFixture fixture = new WorkerFixture();
        fixture.seedEvent();
        ShardingPolicy two = new ShardingPolicy(2_000, 32, 2, 4, 8);
        fixture.store.useSharding(two);
        Order first = fixture.createOrder(CUSTOMER_A, key(6), "A-1-1");
        Order second = fixture.createOrder(WorkerFixture.CUSTOMER_B, key(7), "A-1-2");
        fixture.clock.set(first.reservation().expiresAt());
        ReservationExpirationService expiration = new ReservationExpirationService(fixture.store, fixture.store,
                fixture.clock, worker(fixture.settings, fixture.settings.orderRules(),
                        fixture.settings.reversalSchedule(), fixture.settings.maximumVerificationRepairs(), two));

        assertThat(value(expiration.expireDue(CycleRequest.of(CORRELATION))).count(ItemOutcome.EXPIRED)).isEqualTo(2);
        assertThat(fixture.store.calls(Operation.FIND_DUE_RESERVATIONS)).isEqualTo(2);
        assertThat(fixture.order(first.orderId()).order().status()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(fixture.order(second.orderId()).order().status()).isEqualTo(OrderStatus.EXPIRED);
    }

    @Test
    @DisplayName("IV-015 ADR-024 the provisioning applies the configured maximum of verification repairs")
    void provisioningUsesTheConfiguredRepairs() {
        WorkerFixture fixture = new WorkerFixture();
        String eventId = "10000000-0000-4000-8000-000000000078";
        fixture.store.seedProvisioningEvent(eventId, NOW.plus(Duration.ofDays(20)), ApiFixture.DEFINITION, NOW);
        TicketInventory inventory = mock(TicketInventory.class);
        when(inventory.writeBatch(any(), any())).thenReturn(Mono.empty());
        when(inventory.verify(any(), any())).thenReturn(Mono.just(new InventoryVerification(24, List.of("A-1-1"))));
        EventProvisioningService noRepairs = new EventProvisioningService(fixture.store, inventory, fixture.clock,
                worker(fixture.settings, fixture.settings.orderRules(), fixture.settings.reversalSchedule(), 0,
                        fixture.settings.sharding()));

        MessageDisposition disposition = value(noRepairs.provision(ProvisionEventCommand.readable(eventId, CORRELATION,
                new Delivery(1, false), null)));

        assertThat(disposition).isEqualTo(MessageDisposition.retry(DispositionReason.TRANSIENT_FAILURE));
        org.mockito.Mockito.verify(inventory, org.mockito.Mockito.times(1)).verify(any(), any());
    }

    @Test
    @DisplayName("IV-012 IV-015 the settings reject missing domain rules")
    void settingsRequireTheRules() {
        ApiUseCaseSettings deployed = ApiUseCaseSettings.DEPLOYED;
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ApiUseCaseSettings(deployed.inventoryLimits(),
                        deployed.idempotencyRetention(), deployed.conflictRetryAfter(), deployed.doubleFailureRetryAfter(),
                        deployed.minimumRetryAfter(), 20, 100, 50, 100, deployed.availableCountCacheTtl(), 8, null,
                        deployed.sharding()))
                .isInstanceOf(NullPointerException.class);
        assertThat(deployed.orderRules()).isEqualTo(OrderRules.DEPLOYED);
        assertThat(deployed.sharding()).isEqualTo(ShardingPolicy.DEPLOYED);
        assertThat(RequestRejectedException.class).isNotNull();
        assertThat(DomainErrorCode.VALIDATION_ERROR).isNotNull();
    }

    private static WorkerUseCaseSettings worker(WorkerUseCaseSettings base, OrderRules rules, ReversalSchedule schedule,
            int repairs, ShardingPolicy sharding) {
        return new WorkerUseCaseSettings(base.workerId(), base.paymentLeaseDuration(), base.authorizationMargin(),
                base.provisioningLeaseDuration(), base.inventoryLimits(), base.stalledProvisioningThreshold(),
                base.maximumProvisioningRepublications(), base.republishAge(), base.expirationConcurrency(),
                base.republishConcurrency(), base.reversalConcurrency(), base.cleanupConcurrency(),
                base.maximumReevaluations(), rules, schedule, repairs, sharding);
    }
}
