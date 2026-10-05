package com.nequi.paymentmock.domain;

/** Contract {@code cancellationStatus}. */
public enum CancellationStatus {
    /** The attempt was approved and is now reversed. */
    REVERSED,
    /** The attempt was declined; nothing to reverse. */
    VOIDED,
    /** No result existed (non-existent or in transit); later authorizations are declined (AC-035). */
    REGISTERED_BEFORE_CHARGE
}
