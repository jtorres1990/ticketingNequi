package com.nequi.ticketing.domain.messaging;

import static com.nequi.ticketing.domain.shared.DomainChecks.required;

import com.nequi.ticketing.domain.order.Order;
import com.nequi.ticketing.domain.order.Order.OrderStatus;
import java.time.Duration;
import java.time.Instant;

/** Pure ordered decisions from ticketing.messaging.v2 section 5.1. */
public final class OrderMessagePolicy {

    private OrderMessagePolicy() {
    }

    public static Action decide(Snapshot snapshot, Instant now) {
        required(snapshot, "snapshot");
        required(now, "now");
        if (!snapshot.messageReadable() || !snapshot.orderPresent()) {
            return Action.POISON_KEEP_WITH_SHORT_VISIBILITY;
        }
        if (snapshot.status().terminal()) {
            return Action.DELETE_NOOP;
        }
        if (snapshot.quarantined()) {
            return Action.DELETE_QUARANTINED;
        }
        if (snapshot.ticketConditionFailed()) {
            return Action.QUARANTINE_AND_DELETE;
        }
        if (!snapshot.hasPaymentAttempt()) {
            Duration remaining = Duration.between(now, snapshot.expiresAt());
            if (remaining.compareTo(Order.PAYMENT_CUTOFF) <= 0) {
                return !snapshot.expiresAt().isAfter(now)
                        ? Action.EXPIRE_AND_DELETE
                        : Action.DELETE_WAIT_FOR_EXPIRATION;
            }
            return Action.START_PAYMENT;
        }
        if (snapshot.lease() == Lease.OTHER_ACTIVE) {
            return Action.POSTPONE_TO_LEASE_END;
        }
        if (snapshot.lease() == Lease.EXPIRED) {
            return Action.CLAIM_LEASE;
        }
        return switch (snapshot.providerResult()) {
            case NONE -> Action.AUTHORIZE_PAYMENT;
            case APPROVED -> Action.CONFIRM_AND_DELETE;
            case DECLINED -> Action.REJECT_AND_DELETE;
            case DEFINITIVE_ERROR -> Action.FAIL_WITHOUT_REVERSAL_AND_DELETE;
            case TRANSIENT -> snapshot.lastReception()
                    ? Action.FAIL_WITH_POSSIBLE_REVERSAL_AND_DLQ
                    : Action.RETRY_WITH_BACKOFF;
        };
    }

    public record Snapshot(
            boolean messageReadable,
            boolean orderPresent,
            OrderStatus status,
            boolean quarantined,
            boolean ticketConditionFailed,
            boolean hasPaymentAttempt,
            Instant expiresAt,
            Lease lease,
            ProviderResult providerResult,
            boolean lastReception) {

        public Snapshot {
            if (orderPresent) {
                status = required(status, "orderStatus");
                expiresAt = required(expiresAt, "expiresAt");
                lease = required(lease, "lease");
                providerResult = required(providerResult, "providerResult");
            }
        }
    }

    public enum Lease { NONE, OTHER_ACTIVE, EXPIRED, OWNED }
    public enum ProviderResult { NONE, APPROVED, DECLINED, DEFINITIVE_ERROR, TRANSIENT }

    public enum Action {
        POISON_KEEP_WITH_SHORT_VISIBILITY,
        DELETE_NOOP,
        DELETE_QUARANTINED,
        QUARANTINE_AND_DELETE,
        EXPIRE_AND_DELETE,
        DELETE_WAIT_FOR_EXPIRATION,
        START_PAYMENT,
        POSTPONE_TO_LEASE_END,
        CLAIM_LEASE,
        AUTHORIZE_PAYMENT,
        CONFIRM_AND_DELETE,
        REJECT_AND_DELETE,
        FAIL_WITHOUT_REVERSAL_AND_DELETE,
        RETRY_WITH_BACKOFF,
        FAIL_WITH_POSSIBLE_REVERSAL_AND_DLQ
    }
}
