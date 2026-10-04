package com.nequi.ticketing.application.port.in;

/** Diagnostic reason of a {@link MessageDisposition} (logs and metrics; never exposed to customers). */
public enum DispositionReason {
    /** Rule 1 (both queues): unreadable message, unknown type or version, missing identifier. */
    UNREADABLE_MESSAGE,
    /** Rule 2 (both queues): the Order or Event does not exist. */
    ENTITY_NOT_FOUND,
    /** Orders rule 3 / provisioning rule 3: the entity is already terminal (duplicate or late delivery). */
    ALREADY_TERMINAL,
    /** Orders rule 4: the Order is quarantined for manual review. */
    QUARANTINED,
    /** Orders rule 15: the transition was cancelled by a Ticket condition and the Order was quarantined. */
    QUARANTINE_APPLIED,
    /** Orders rule 5: payment not started inside the cutoff; the Order is left to expiration. */
    PAYMENT_CUTOFF,
    /** Orders rule 5 or the late approval path: the Order was expired. */
    EXPIRED,
    /** Orders rule 10. */
    CONFIRMED,
    /** Orders rule 10 on an Order already closed: AP-032 recorded the late approval. */
    LATE_APPROVAL_RECORDED,
    /** Orders rule 11. */
    REJECTED,
    /** Orders rule 12: definitive provider error, failed without reversal. */
    FAILED_DEFINITIVE,
    /** Orders rule 7 / provisioning rule 4: a lease of another consumer is in force. */
    LEASE_HELD_ELSEWHERE,
    /** Orders rule 13 / provisioning rules 6 and 9: transient failure with receptions left. */
    TRANSIENT_FAILURE,
    /** Orders rule 14 / provisioning rule 10: transient failure on the last reception; the entity was closed. */
    EXHAUSTED,
    /** Provisioning rule 8. */
    ENABLED
}
