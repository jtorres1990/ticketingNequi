package com.nequi.ticketing.domain.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.nequi.ticketing.domain.event.Event.ProvisioningStatus;
import com.nequi.ticketing.domain.messaging.OrderMessagePolicy.Action;
import com.nequi.ticketing.domain.messaging.OrderMessagePolicy.Lease;
import com.nequi.ticketing.domain.messaging.OrderMessagePolicy.ProviderResult;
import com.nequi.ticketing.domain.order.Order.OrderStatus;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class MessagePolicyTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @ParameterizedTest(name = "orders rule chooses {1}")
    @MethodSource("orderCases")
    @DisplayName("Messaging 5.1 evaluates Order rules in normative order")
    void evaluatesOrderRules(OrderMessagePolicy.Snapshot snapshot, Action expected) {
        assertThat(OrderMessagePolicy.decide(snapshot, NOW)).isEqualTo(expected);
    }

    static Stream<Arguments> orderCases() {
        return Stream.of(
                Arguments.of(snapshot(false, false, OrderStatus.CREATED, false, false, false,
                        60, Lease.NONE, ProviderResult.NONE, false), Action.POISON_KEEP_WITH_SHORT_VISIBILITY),
                Arguments.of(snapshot(true, true, OrderStatus.CONFIRMED, false, false, true,
                        60, Lease.OWNED, ProviderResult.APPROVED, false), Action.DELETE_NOOP),
                Arguments.of(snapshot(true, true, OrderStatus.CREATED, true, false, false,
                        60, Lease.NONE, ProviderResult.NONE, false), Action.DELETE_QUARANTINED),
                Arguments.of(snapshot(true, true, OrderStatus.CREATED, false, true, false,
                        60, Lease.NONE, ProviderResult.NONE, false), Action.QUARANTINE_AND_DELETE),
                Arguments.of(snapshot(true, true, OrderStatus.CREATED, false, false, false,
                        0, Lease.NONE, ProviderResult.NONE, false), Action.EXPIRE_AND_DELETE),
                Arguments.of(snapshot(true, true, OrderStatus.CREATED, false, false, false,
                        14, Lease.NONE, ProviderResult.NONE, false), Action.DELETE_WAIT_FOR_EXPIRATION),
                Arguments.of(snapshot(true, true, OrderStatus.CREATED, false, false, false,
                        15, Lease.NONE, ProviderResult.NONE, false), Action.DELETE_WAIT_FOR_EXPIRATION),
                Arguments.of(snapshot(true, true, OrderStatus.CREATED, false, false, false,
                        16, Lease.NONE, ProviderResult.NONE, false), Action.START_PAYMENT),
                Arguments.of(snapshot(true, true, OrderStatus.CREATED, false, false, true,
                        60, Lease.OTHER_ACTIVE, ProviderResult.NONE, false), Action.POSTPONE_TO_LEASE_END),
                Arguments.of(snapshot(true, true, OrderStatus.CREATED, false, false, true,
                        60, Lease.EXPIRED, ProviderResult.NONE, false), Action.CLAIM_LEASE),
                Arguments.of(snapshot(true, true, OrderStatus.CREATED, false, false, true,
                        60, Lease.OWNED, ProviderResult.NONE, false), Action.AUTHORIZE_PAYMENT),
                Arguments.of(snapshot(true, true, OrderStatus.CREATED, false, false, true,
                        60, Lease.OWNED, ProviderResult.APPROVED, false), Action.CONFIRM_AND_DELETE),
                Arguments.of(snapshot(true, true, OrderStatus.CREATED, false, false, true,
                        60, Lease.OWNED, ProviderResult.DECLINED, false), Action.REJECT_AND_DELETE),
                Arguments.of(snapshot(true, true, OrderStatus.CREATED, false, false, true,
                        60, Lease.OWNED, ProviderResult.DEFINITIVE_ERROR, false), Action.FAIL_WITHOUT_REVERSAL_AND_DELETE),
                Arguments.of(snapshot(true, true, OrderStatus.CREATED, false, false, true,
                        60, Lease.OWNED, ProviderResult.TRANSIENT, false), Action.RETRY_WITH_BACKOFF),
                Arguments.of(snapshot(true, true, OrderStatus.CREATED, false, false, true,
                        60, Lease.OWNED, ProviderResult.TRANSIENT, true), Action.FAIL_WITH_POSSIBLE_REVERSAL_AND_DLQ));
    }

    @ParameterizedTest(name = "provisioning rule chooses {1}")
    @MethodSource("provisioningCases")
    @DisplayName("Messaging 5.2 evaluates provisioning rules in normative order")
    void evaluatesProvisioningRules(ProvisioningMessagePolicy.Snapshot snapshot,
            ProvisioningMessagePolicy.Action expected) {
        assertThat(ProvisioningMessagePolicy.decide(snapshot)).isEqualTo(expected);
    }

    static Stream<Arguments> provisioningCases() {
        return Stream.of(
                Arguments.of(provisioning(false, false, ProvisioningStatus.PROVISIONING,
                        false, false, false, false, false, 0, false, false),
                        ProvisioningMessagePolicy.Action.POISON_KEEP_WITH_SHORT_VISIBILITY),
                Arguments.of(provisioning(true, true, ProvisioningStatus.ENABLED,
                        false, false, false, true, true, 0, false, false),
                        ProvisioningMessagePolicy.Action.DELETE_NOOP),
                Arguments.of(provisioning(true, true, ProvisioningStatus.PROVISIONING,
                        true, false, false, false, false, 0, false, false),
                        ProvisioningMessagePolicy.Action.POSTPONE_TO_LEASE_END),
                Arguments.of(provisioning(true, true, ProvisioningStatus.PROVISIONING,
                        false, false, false, false, false, 0, true, false),
                        ProvisioningMessagePolicy.Action.RETRY_WITH_BACKOFF),
                Arguments.of(provisioning(true, true, ProvisioningStatus.PROVISIONING,
                        false, false, false, false, false, 0, true, true),
                        ProvisioningMessagePolicy.Action.FAIL_AND_DLQ),
                Arguments.of(provisioning(true, true, ProvisioningStatus.PROVISIONING,
                        false, false, false, false, false, 0, false, false),
                        ProvisioningMessagePolicy.Action.ACQUIRE_LEASE),
                Arguments.of(provisioning(true, true, ProvisioningStatus.PROVISIONING,
                        false, true, true, false, false, 0, false, false),
                        ProvisioningMessagePolicy.Action.REREAD),
                Arguments.of(provisioning(true, true, ProvisioningStatus.PROVISIONING,
                        false, true, false, false, false, 0, false, false),
                        ProvisioningMessagePolicy.Action.WRITE_NEXT_BATCH),
                Arguments.of(provisioning(true, true, ProvisioningStatus.PROVISIONING,
                        false, true, false, true, false, 2, false, false),
                        ProvisioningMessagePolicy.Action.REPAIR_AND_VERIFY),
                Arguments.of(provisioning(true, true, ProvisioningStatus.PROVISIONING,
                        false, true, false, true, false, 3, false, false),
                        ProvisioningMessagePolicy.Action.FAIL_AND_DELETE),
                Arguments.of(provisioning(true, true, ProvisioningStatus.PROVISIONING,
                        false, true, false, true, true, 3, false, false),
                        ProvisioningMessagePolicy.Action.ENABLE_AND_DELETE));
    }

    private static OrderMessagePolicy.Snapshot snapshot(
            boolean readable, boolean present, OrderStatus status, boolean quarantined,
            boolean ticketFailure, boolean attempt, long remainingSeconds, Lease lease,
            ProviderResult result, boolean last) {
        return new OrderMessagePolicy.Snapshot(readable, present, status, quarantined, ticketFailure, attempt,
                NOW.plusSeconds(remainingSeconds), lease, result, last);
    }

    private static ProvisioningMessagePolicy.Snapshot provisioning(
            boolean readable, boolean present, ProvisioningStatus status, boolean otherLease, boolean owned,
            boolean preBatchFailure, boolean allWritten, boolean verified, int verificationAttempts,
            boolean transientFailure, boolean last) {
        return new ProvisioningMessagePolicy.Snapshot(readable, present, status, otherLease, owned,
                preBatchFailure, allWritten, verified, verificationAttempts, transientFailure, last);
    }
}
