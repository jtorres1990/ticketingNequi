package com.nequi.ticketing.application.port.in;

import java.util.Objects;

/**
 * One delivery of MSG-002 {@code EventProvisioningRequested}. {@code progress} is notified after every
 * written batch so that the consumer adapter extends the visibility only while there is progress
 * (heartbeat, ADR-029). An unreadable message is passed as {@link #unreadable(String, Delivery)}.
 */
public record ProvisionEventCommand(
        String eventId,
        String correlationId,
        Delivery delivery,
        String unreadableReason,
        ProvisioningProgressListener progress) {

    public ProvisionEventCommand {
        Objects.requireNonNull(delivery, "delivery");
        progress = progress == null ? ProvisioningProgressListener.NONE : progress;
        if ((eventId == null) == (unreadableReason == null)) {
            throw new IllegalArgumentException("a message is either readable with an eventId or unreadable with a reason");
        }
        if (eventId != null) {
            Objects.requireNonNull(correlationId, "correlationId");
        }
    }

    public static ProvisionEventCommand readable(String eventId, String correlationId, Delivery delivery,
            ProvisioningProgressListener progress) {
        return new ProvisionEventCommand(Objects.requireNonNull(eventId, "eventId"), correlationId, delivery, null,
                progress);
    }

    public static ProvisionEventCommand unreadable(String reason, Delivery delivery) {
        return new ProvisionEventCommand(null, null, delivery, Objects.requireNonNull(reason, "reason"), null);
    }

    public boolean readable() {
        return eventId != null;
    }
}
