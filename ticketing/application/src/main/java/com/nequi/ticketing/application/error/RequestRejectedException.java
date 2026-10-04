package com.nequi.ticketing.application.error;

import com.nequi.ticketing.domain.error.DomainErrorCode;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Functional rejection decided by a use case, identified by a stable domain code (ADR-035). It never
 * carries technical detail. Instances are created only through the factories, one per code.
 * Input validation failures are signalled with the domain
 * {@code ValidationException}; the inbound adapter translates both in a single place.
 */
public final class RequestRejectedException extends RuntimeException {

    private final DomainErrorCode code;
    private final List<String> ticketIds;
    private final Duration retryAfter;

    private RequestRejectedException(DomainErrorCode code, List<String> ticketIds, Duration retryAfter) {
        super(code.name(), null, false, false);
        this.code = code;
        this.ticketIds = List.copyOf(ticketIds);
        this.retryAfter = retryAfter;
    }

    public static RequestRejectedException eventNotFound() {
        return new RequestRejectedException(DomainErrorCode.EVENT_NOT_FOUND, List.of(), null);
    }

    public static RequestRejectedException orderNotFound() {
        return new RequestRejectedException(DomainErrorCode.ORDER_NOT_FOUND, List.of(), null);
    }

    public static RequestRejectedException eventNotOnSale() {
        return new RequestRejectedException(DomainErrorCode.EVENT_NOT_ON_SALE, List.of(), null);
    }

    public static RequestRejectedException activeOrderExists() {
        return new RequestRejectedException(DomainErrorCode.ACTIVE_ORDER_EXISTS, List.of(), null);
    }

    public static RequestRejectedException idempotencyKeyReused() {
        return new RequestRejectedException(DomainErrorCode.IDEMPOTENCY_KEY_REUSED, List.of(), null);
    }

    public static RequestRejectedException ticketsUnavailable(List<String> ticketIds) {
        return new RequestRejectedException(DomainErrorCode.TICKETS_UNAVAILABLE, ticketIds, null);
    }

    public static RequestRejectedException unknownTickets(List<String> ticketIds) {
        return new RequestRejectedException(DomainErrorCode.UNKNOWN_TICKETS, ticketIds, null);
    }

    public static RequestRejectedException serviceUnavailable(Duration retryAfter) {
        return new RequestRejectedException(
                DomainErrorCode.SERVICE_UNAVAILABLE, List.of(), Objects.requireNonNull(retryAfter, "retryAfter"));
    }

    public DomainErrorCode code() {
        return code;
    }

    /** Tickets reported by {@code TICKETS_UNAVAILABLE} or {@code UNKNOWN_TICKETS}; empty otherwise. */
    public List<String> ticketIds() {
        return ticketIds;
    }

    /** Present only for {@code SERVICE_UNAVAILABLE}. */
    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}
