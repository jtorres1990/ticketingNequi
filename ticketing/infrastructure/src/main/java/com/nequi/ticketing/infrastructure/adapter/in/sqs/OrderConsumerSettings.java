package com.nequi.ticketing.infrastructure.adapter.in.sqs;

import java.time.Duration;
import java.util.Objects;

/**
 * Configurable values of the Orders loop (CMP-012): the loop values and the processing cap per message of
 * 30 s, below the payment lease of 45 s and the visibility timeout of 60 s (ADR-029).
 */
public record OrderConsumerSettings(ConsumerLoopSettings loop, Duration processingCap) {

    public OrderConsumerSettings {
        Objects.requireNonNull(loop, "loop");
        ConsumerLoopSettings.requirePositive(processingCap, "processingCap");
    }

    public static OrderConsumerSettings deployed(String queueUrl) {
        return new OrderConsumerSettings(ConsumerLoopSettings.orders(queueUrl), Duration.ofSeconds(30));
    }
}
