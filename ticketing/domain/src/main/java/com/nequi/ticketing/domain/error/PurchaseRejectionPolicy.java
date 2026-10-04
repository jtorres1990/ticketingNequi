package com.nequi.ticketing.domain.error;

import java.util.List;

/** BR-031 precedence. The first present rejection is the externally visible result. */
public final class PurchaseRejectionPolicy {

    private static final List<DomainErrorCode> PRECEDENCE = List.of(
            DomainErrorCode.VALIDATION_ERROR,
            DomainErrorCode.IDEMPOTENCY_KEY_REUSED,
            DomainErrorCode.EVENT_NOT_FOUND,
            DomainErrorCode.EVENT_NOT_ON_SALE,
            DomainErrorCode.UNKNOWN_TICKETS,
            DomainErrorCode.SERVICE_UNAVAILABLE,
            DomainErrorCode.ACTIVE_ORDER_EXISTS,
            DomainErrorCode.TICKETS_UNAVAILABLE);

    private PurchaseRejectionPolicy() {
    }

    public static DomainErrorCode firstOf(Iterable<DomainErrorCode> failures) {
        for (DomainErrorCode candidate : PRECEDENCE) {
            for (DomainErrorCode failure : failures) {
                if (candidate == failure) {
                    return candidate;
                }
            }
        }
        throw new IllegalArgumentException("no purchase rejection was supplied");
    }

    public static List<DomainErrorCode> precedence() {
        return PRECEDENCE;
    }
}
