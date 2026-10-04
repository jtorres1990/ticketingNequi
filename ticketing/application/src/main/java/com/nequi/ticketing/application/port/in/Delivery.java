package com.nequi.ticketing.application.port.in;

/**
 * Delivery attempt of a queue message: {@code ApproximateReceiveCount} and whether it is the last
 * reception allowed by {@code maxReceiveCount} (ADR-029). The SQS message identifier is not an identity.
 */
public record Delivery(int receiveCount, boolean lastReception) {

    public Delivery {
        if (receiveCount < 1) {
            throw new IllegalArgumentException("receiveCount starts at 1");
        }
    }
}
