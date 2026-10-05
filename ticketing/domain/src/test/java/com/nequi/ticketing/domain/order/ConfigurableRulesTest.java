package com.nequi.ticketing.domain.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nequi.ticketing.domain.error.InvalidStateTransitionException;
import com.nequi.ticketing.domain.error.ValidationException;
import com.nequi.ticketing.domain.event.Event;
import com.nequi.ticketing.domain.event.Event.ProvisioningStatus;
import com.nequi.ticketing.domain.event.InventoryDefinition;
import com.nequi.ticketing.domain.event.InventoryLimits;
import com.nequi.ticketing.domain.event.ShardingPolicy;
import com.nequi.ticketing.domain.messaging.OrderMessagePolicy;
import com.nequi.ticketing.domain.messaging.ProvisioningMessagePolicy;
import com.nequi.ticketing.domain.order.Order.OrderStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** IV-012 and IV-015: the domain constants declared configurable by spec §13.1 and plan Annex A. */
class ConfigurableRulesTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    @DisplayName("IV-012 IV-015 the approved defaults are 10 Tickets, 15 s cutoff, 3 repairs, 10 reversal attempts and 8/4/8 shards")
    void approvedDefaults() {
        assertThat(OrderRules.DEPLOYED).isEqualTo(new OrderRules(10, Duration.ofSeconds(15)));
        assertThat(OrderRules.DEPLOYED.paymentCutoff()).isEqualTo(Order.PAYMENT_CUTOFF);
        assertThat(ReversalSchedule.DEPLOYED.maximumAttempts()).isEqualTo(ReversalPlan.MAXIMUM_ATTEMPTS).isEqualTo(10);
        assertThat(IntStream.range(0, 10).mapToObj(ReversalSchedule.DEPLOYED::delayAfter).toList()).containsExactly(
                Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofMinutes(2),
                Duration.ofMinutes(5), Duration.ofMinutes(10), Duration.ofMinutes(10), Duration.ofMinutes(10),
                Duration.ofMinutes(10), Duration.ofMinutes(10));
        assertThat(ProvisioningMessagePolicy.MAXIMUM_REPAIRS).isEqualTo(3);
        assertThat(ShardingPolicy.DEPLOYED).isEqualTo(new ShardingPolicy(2_000, 32, 8, 4, 8));
    }

    @Test
    @DisplayName("IV-012 VAL-012 the configured maximum of Tickets per Order is applied, never above the transaction bound of 10")
    void configurableMaximumTickets() {
        OrderRules three = new OrderRules(3, Duration.ofSeconds(15));
        assertThat(PurchaseRequest.of("event", List.of("A-1-1", "A-1-2", "A-1-3"), "purchase-key-0000001", three)
                .ticketIds()).hasSize(3);
        assertThatThrownBy(() -> PurchaseRequest.of("event", List.of("A-1-1", "A-1-2", "A-1-3", "A-1-4"),
                "purchase-key-0000001", three))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("between 1 and 3");
        assertThat(PurchaseRequest.of("event", tickets(10), "purchase-key-0000001", OrderRules.DEPLOYED).ticketIds())
                .hasSize(10);
        assertThatThrownBy(() -> PurchaseRequest.of("event", tickets(11), "purchase-key-0000001", OrderRules.DEPLOYED))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("between 1 and 10");
        assertThatThrownBy(() -> PurchaseRequest.of("event", List.of(), "purchase-key-0000001", three))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> PurchaseRequest.of(null, List.of("A-1-1"), "purchase-key-0000001", three))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new OrderRules(11, Duration.ofSeconds(15))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderRules(0, Duration.ofSeconds(15))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderRules(10, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderRules(10, Duration.ofMinutes(10))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("IV-015 BR-029 the configured payment cutoff margin governs the payment start and the message rule")
    void configurablePaymentCutoff() {
        Order order = Order.create("order", "customer", new PurchaseRequest("event", List.of("A-1-1"),
                "purchase-key-0000001"), NOW);
        Instant expiresAt = order.reservation().expiresAt();
        Duration twenty = Duration.ofSeconds(20);

        assertThat(order.startPayment(expiresAt.minusSeconds(16)).paymentAttempt()).isNotNull();
        assertThatThrownBy(() -> order.startPayment(expiresAt.minusSeconds(16), twenty))
                .isInstanceOf(InvalidStateTransitionException.class);
        assertThat(order.startPayment(expiresAt.minusSeconds(21), twenty).paymentAttempt()).isNotNull();

        OrderMessagePolicy.Snapshot snapshot = new OrderMessagePolicy.Snapshot(true, true, OrderStatus.CREATED,
                false, false, false, expiresAt, OrderMessagePolicy.Lease.NONE, OrderMessagePolicy.ProviderResult.NONE,
                false);
        assertThat(OrderMessagePolicy.decide(snapshot, expiresAt.minusSeconds(16)))
                .isEqualTo(OrderMessagePolicy.Action.START_PAYMENT);
        assertThat(OrderMessagePolicy.decide(snapshot, expiresAt.minusSeconds(16), twenty))
                .isEqualTo(OrderMessagePolicy.Action.DELETE_WAIT_FOR_EXPIRATION);
    }

    @Test
    @DisplayName("IV-015 ADR-025 the configured reversal schedule sets the backoff and exhausts at its maximum of attempts")
    void configurableReversalSchedule() {
        ReversalSchedule schedule = new ReversalSchedule(List.of(Duration.ofSeconds(1), Duration.ofSeconds(2)), 3);
        assertThat(schedule.delayAfter(0)).isEqualTo(Duration.ofSeconds(1));
        assertThat(schedule.delayAfter(2)).isEqualTo(Duration.ofSeconds(2));
        assertThatThrownBy(() -> schedule.delayAfter(3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> schedule.delayAfter(-1)).isInstanceOf(IllegalArgumentException.class);

        Order failed = Order.create("order", "customer", new PurchaseRequest("event", List.of("A-1-1"),
                "purchase-key-0000001"), NOW).startPayment(NOW).failProcessing(NOW.plusSeconds(1));
        Order first = failed.rescheduleReversal(NOW.plusSeconds(2), schedule);
        assertThat(first.reversalPlan().nextAttemptAt()).isEqualTo(NOW.plusSeconds(3));
        Order second = first.rescheduleReversal(NOW.plusSeconds(3), schedule);
        assertThat(second.reversalPlan().nextAttemptAt()).isEqualTo(NOW.plusSeconds(5));
        Order exhausted = second.rescheduleReversal(NOW.plusSeconds(5), schedule);
        assertThat(exhausted.reversalPlan().exhausted()).isTrue();
        assertThat(exhausted.reversalPlan().attempts()).isEqualTo(3);
        assertThat(failed.rescheduleReversal(NOW.plusSeconds(2)).reversalPlan().nextAttemptAt())
                .isEqualTo(NOW.plusSeconds(12));

        assertThatThrownBy(() -> new ReversalSchedule(List.of(), 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReversalSchedule(List.of(Duration.ZERO), 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReversalSchedule(List.of(Duration.ofSeconds(1)), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("IV-015 ADR-024 the configured maximum of verification repairs decides between repairing and retrying")
    void configurableRepairs() {
        ProvisioningMessagePolicy.Snapshot snapshot = new ProvisioningMessagePolicy.Snapshot(true, true,
                ProvisioningStatus.PROVISIONING, false, true, false, true, false, 1, false, false);
        assertThat(ProvisioningMessagePolicy.decide(snapshot)).isEqualTo(ProvisioningMessagePolicy.Action.REPAIR_AND_VERIFY);
        assertThat(ProvisioningMessagePolicy.decide(snapshot, 1)).isEqualTo(ProvisioningMessagePolicy.Action.RETRY_WITH_BACKOFF);
        assertThat(ProvisioningMessagePolicy.decide(snapshot, 2)).isEqualTo(ProvisioningMessagePolicy.Action.REPAIR_AND_VERIFY);
        assertThatThrownBy(() -> ProvisioningMessagePolicy.decide(snapshot, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("IV-015 ADR-022 the configured shard counts and availability divisor are applied")
    void configurableSharding() {
        ShardingPolicy sharding = new ShardingPolicy(1_000, 4, 2, 3, 5);
        assertThat(sharding.availabilityShardsFor(10_000)).isEqualTo(4);
        assertThat(sharding.availabilityShardsFor(1_500)).isEqualTo(2);
        assertThat(ShardingPolicy.availabilityShards(10_000)).isEqualTo(5);
        assertThat(sharding.reservationShard("order-1")).isEqualTo(ShardingPolicy.shard("order-1", 2));
        assertThat(sharding.reversalShard("order-1")).isEqualTo(ShardingPolicy.shard("order-1", 3));
        assertThat(sharding.pendingEnqueueShard("order-1")).isEqualTo(ShardingPolicy.shard("order-1", 5));
        assertThatThrownBy(() -> new ShardingPolicy(0, 4, 2, 3, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ShardingPolicy(1, 4, 2, 0, 5)).isInstanceOf(IllegalArgumentException.class);

        InventoryDefinition definition = new InventoryDefinition(List.of(new InventoryDefinition.Section("A",
                List.of(new InventoryDefinition.Row("1", 1_000), new InventoryDefinition.Row("2", 1_000),
                        new InventoryDefinition.Row("3", 1_000)))), List.of());
        Event event = Event.create("event", "Concert", "Arena", NOW.plusSeconds(3_600), 3_000, definition, NOW,
                InventoryLimits.DEPLOYED, sharding);
        assertThat(event.availabilityShards()).isEqualTo(3);
        assertThatThrownBy(() -> Event.create("event", "Concert", "Arena", NOW.plusSeconds(3_600), 3_000, definition,
                NOW, InventoryLimits.DEPLOYED, null)).isInstanceOf(ValidationException.class);
    }

    private static List<String> tickets(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(seat -> "A-1-" + seat).toList();
    }
}
