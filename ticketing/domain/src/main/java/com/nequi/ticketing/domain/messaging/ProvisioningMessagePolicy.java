package com.nequi.ticketing.domain.messaging;

import static com.nequi.ticketing.domain.shared.DomainChecks.required;

import com.nequi.ticketing.domain.event.Event.ProvisioningStatus;

/** Pure ordered decisions from ticketing.messaging.v2 section 5.2. */
public final class ProvisioningMessagePolicy {

    /** ADR-024: missing Tickets are rewritten and verified again up to three times. */
    public static final int MAXIMUM_REPAIRS = 3;

    private ProvisioningMessagePolicy() {
    }

    public static Action decide(Snapshot snapshot) {
        required(snapshot, "snapshot");
        if (!snapshot.messageReadable() || !snapshot.eventPresent()) {
            return Action.POISON_KEEP_WITH_SHORT_VISIBILITY;
        }
        if (snapshot.status().terminal()) {
            return Action.DELETE_NOOP;
        }
        if (snapshot.otherLeaseActive()) {
            return Action.POSTPONE_TO_LEASE_END;
        }
        if (snapshot.transientFailure()) {
            return snapshot.lastReception() ? Action.FAIL_AND_DLQ : Action.RETRY_WITH_BACKOFF;
        }
        if (!snapshot.leaseOwned()) {
            return Action.ACQUIRE_LEASE;
        }
        if (snapshot.preBatchConditionFailed()) {
            return Action.REREAD;
        }
        if (!snapshot.allBatchesWritten()) {
            return Action.WRITE_NEXT_BATCH;
        }
        if (!snapshot.inventoryVerified()) {
            if (snapshot.verificationAttempts() < MAXIMUM_REPAIRS) {
                return Action.REPAIR_AND_VERIFY;
            }
            // ADR-024 section 3.9: a verification that keeps failing is a transient failure of the message.
            return snapshot.lastReception() ? Action.FAIL_AND_DLQ : Action.RETRY_WITH_BACKOFF;
        }
        return Action.ENABLE_AND_DELETE;
    }

    public record Snapshot(
            boolean messageReadable,
            boolean eventPresent,
            ProvisioningStatus status,
            boolean otherLeaseActive,
            boolean leaseOwned,
            boolean preBatchConditionFailed,
            boolean allBatchesWritten,
            boolean inventoryVerified,
            int verificationAttempts,
            boolean transientFailure,
            boolean lastReception) {

        public Snapshot {
            if (eventPresent) {
                status = required(status, "provisioningStatus");
            }
            if (verificationAttempts < 0) {
                throw new IllegalArgumentException("verificationAttempts cannot be negative");
            }
        }
    }

    public enum Action {
        POISON_KEEP_WITH_SHORT_VISIBILITY,
        DELETE_NOOP,
        POSTPONE_TO_LEASE_END,
        ACQUIRE_LEASE,
        REREAD,
        WRITE_NEXT_BATCH,
        REPAIR_AND_VERIFY,
        ENABLE_AND_DELETE,
        RETRY_WITH_BACKOFF,
        FAIL_AND_DLQ
    }
}
