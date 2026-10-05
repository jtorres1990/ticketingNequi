package com.nequi.ticketing.infrastructure.adapter.in.sqs;

import java.time.Duration;
import java.util.Objects;

/**
 * Configurable values of the provisioning loop (CMP-025): the loop values and the visibility heartbeat of
 * ADR-029 / ADR-039, which extends the visibility to 120 s every 30 s while the use case reports progress
 * and stops for good when there was no progress within 60 s. There is no processing cap while there is
 * progress.
 */
public record ProvisioningConsumerSettings(
        ConsumerLoopSettings loop,
        Duration heartbeatInterval,
        Duration heartbeatVisibility,
        Duration noProgressWindow) {

    public ProvisioningConsumerSettings {
        Objects.requireNonNull(loop, "loop");
        ConsumerLoopSettings.requirePositive(heartbeatInterval, "heartbeatInterval");
        ConsumerLoopSettings.requirePositive(heartbeatVisibility, "heartbeatVisibility");
        ConsumerLoopSettings.requirePositive(noProgressWindow, "noProgressWindow");
        if (heartbeatInterval.compareTo(heartbeatVisibility) >= 0) {
            throw new IllegalArgumentException("heartbeatInterval must be shorter than heartbeatVisibility");
        }
    }

    public static ProvisioningConsumerSettings deployed(String queueUrl) {
        return new ProvisioningConsumerSettings(ConsumerLoopSettings.provisioning(queueUrl),
                Duration.ofSeconds(30), Duration.ofSeconds(120), Duration.ofSeconds(60));
    }
}
