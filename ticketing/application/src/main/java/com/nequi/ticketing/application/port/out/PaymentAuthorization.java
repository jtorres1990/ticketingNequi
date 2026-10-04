package com.nequi.ticketing.application.port.out;

import java.util.List;
import java.util.Objects;

/**
 * Authorization request of one PaymentAttempt (ADR-030): {@code customerRef} is the JWT subject of the
 * Order owner; no personal data nor tokens are sent.
 */
public record PaymentAuthorization(
        String paymentAttemptId,
        String orderId,
        String eventId,
        String customerRef,
        List<String> ticketIds) {

    public PaymentAuthorization {
        Objects.requireNonNull(paymentAttemptId, "paymentAttemptId");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(customerRef, "customerRef");
        ticketIds = List.copyOf(Objects.requireNonNull(ticketIds, "ticketIds"));
    }
}
