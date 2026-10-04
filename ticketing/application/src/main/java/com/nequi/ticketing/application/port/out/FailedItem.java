package com.nequi.ticketing.application.port.out;

/** Item of an atomic write whose condition was not met (ADR-023, ADR-025, ADR-039). */
public enum FailedItem {
    /** The idempotency record already exists. */
    IDEMPOTENCY_RECORD,
    /** The active Order lock exists, or belongs to another Order. */
    ACTIVE_ORDER_LOCK,
    /** The Order guard failed (already exists, or not in the required state). */
    ORDER,
    /** The Event item condition failed. */
    EVENT,
    /** The audit record already exists. */
    AUDIT,
    /** A requested Ticket item does not exist (or belongs to another Event). */
    TICKET_MISSING,
    /** A Ticket exists but is not in the expected state or owned by the expected Order. */
    TICKET_STATE
}
