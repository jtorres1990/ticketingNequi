package com.nequi.paymentmock.domain;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Data of an authorization request that rules can match and that is fixed for a payment attempt by its first valid
 * invocation (PM-IV-010). {@code ticketIds} is compared as a set.
 */
public record AttemptPayload(String orderId, String eventId, String customerRef, Set<String> ticketIds) {

    public AttemptPayload {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(customerRef, "customerRef");
        ticketIds = Collections.unmodifiableSet(new LinkedHashSet<>(Objects.requireNonNull(ticketIds, "ticketIds")));
    }

    public static AttemptPayload of(String orderId, String eventId, String customerRef, List<String> ticketIds) {
        return new AttemptPayload(orderId, eventId, customerRef, new LinkedHashSet<>(ticketIds));
    }

    /** Never prints the customer reference (ADR-032). */
    @Override
    public String toString() {
        return "AttemptPayload[orderId=" + orderId + ", eventId=" + eventId + ", tickets=" + ticketIds.size() + "]";
    }
}
