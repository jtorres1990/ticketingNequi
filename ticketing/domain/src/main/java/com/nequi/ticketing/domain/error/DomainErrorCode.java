package com.nequi.ticketing.domain.error;

/** Stable domain codes. HTTP translation belongs to an inbound adapter (ADR-035). */
public enum DomainErrorCode {
    VALIDATION_ERROR,
    EVENT_NOT_FOUND,
    ORDER_NOT_FOUND,
    TICKETS_UNAVAILABLE,
    EVENT_NOT_ON_SALE,
    ACTIVE_ORDER_EXISTS,
    UNKNOWN_TICKETS,
    IDEMPOTENCY_KEY_REUSED,
    SERVICE_UNAVAILABLE,
    UNAUTHENTICATED,
    FORBIDDEN,
    PAYLOAD_TOO_LARGE,
    RATE_LIMITED,
    INTERNAL_ERROR,
    INVALID_STATE_TRANSITION,
    INCONSISTENT_TICKET_STATE
}
