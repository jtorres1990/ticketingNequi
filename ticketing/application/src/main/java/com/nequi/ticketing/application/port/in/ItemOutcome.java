package com.nequi.ticketing.application.port.in;

/** Result of one candidate of a periodic cycle (ADR-028). */
public enum ItemOutcome {
    /** CMP-008: Order expired and its Tickets released. */
    EXPIRED,
    /** CMP-008: expiration cancelled by a Ticket condition, Order quarantined (ADR-025). */
    QUARANTINED,
    /** CMP-023: MSG-001 republished. */
    REPUBLISHED,
    /** CMP-024: cancellation confirmed, reversal completed. */
    REVERSAL_CONFIRMED,
    /** CMP-024: transient cancellation failure, next attempt scheduled. */
    REVERSAL_RESCHEDULED,
    /** CMP-024: tenth failure, reversal left for manual review. */
    REVERSAL_EXHAUSTED,
    /** CMP-015: stalled provisioning republished (MSG-002). */
    PROVISIONING_REPUBLISHED,
    /** CMP-015: stalled provisioning marked {@code FAILED}. */
    PROVISIONING_FAILED,
    /** CMP-015: Tickets of a {@code FAILED} Event purged. */
    TICKETS_PURGED,
    /** The candidate no longer qualified (stale index entry, concurrent change, condition not met). */
    NOT_APPLICABLE,
    /** The candidate could not be processed in this cycle (transient error); the next cycle retries. */
    FAILED
}
