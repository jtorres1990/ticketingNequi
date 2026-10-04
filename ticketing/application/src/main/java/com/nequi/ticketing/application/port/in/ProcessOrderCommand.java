package com.nequi.ticketing.application.port.in;

import java.util.Objects;

/**
 * One delivery of MSG-001 {@code OrderProcessingRequested}. A message that the adapter could not read
 * (unknown type or version, missing {@code orderId}) is passed as {@link #unreadable(String, Delivery)}
 * so that rule 1 of {@code ticketing.messaging.v2.md} §5.1 is applied by the use case.
 */
public record ProcessOrderCommand(String orderId, String correlationId, Delivery delivery, String unreadableReason) {

    public ProcessOrderCommand {
        Objects.requireNonNull(delivery, "delivery");
        if ((orderId == null) == (unreadableReason == null)) {
            throw new IllegalArgumentException("a message is either readable with an orderId or unreadable with a reason");
        }
        if (orderId != null) {
            Objects.requireNonNull(correlationId, "correlationId");
        }
    }

    public static ProcessOrderCommand readable(String orderId, String correlationId, Delivery delivery) {
        return new ProcessOrderCommand(Objects.requireNonNull(orderId, "orderId"), correlationId, delivery, null);
    }

    public static ProcessOrderCommand unreadable(String reason, Delivery delivery) {
        return new ProcessOrderCommand(null, null, delivery, Objects.requireNonNull(reason, "reason"));
    }

    public boolean readable() {
        return orderId != null;
    }
}
