package com.nequi.ticketing.infrastructure.adapter.sqs;

import java.time.Duration;

/**
 * Observation hook of the SQS adapters (CMP-011, CMP-012, CMP-025). The adapters never log or record metrics
 * on reactive threads themselves; they report here, and the observability wiring of INC-010 (ADR-037)
 * turns these notifications into structured logs and metrics. Implementations must not block (NFR-003).
 * Every method has a no-op default.
 */
public interface SqsEvents {

    SqsEvents NONE = new SqsEvents() {
    };

    /** A publication ended as {@code FAILED} (definitive after the bounded policy, or circuit open). */
    default void publicationFailed(String queue, Throwable cause) {
    }

    /** A received message could not be read and is handled as poison. */
    default void unreadableMessage(String queue, String messageId, String reason) {
    }

    /** The use case failed or exceeded the processing cap; the message is retried by visibility backoff. */
    default void processingFailed(String queue, String messageId, Throwable cause) {
    }

    /** Deleting a message or changing its visibility failed; SQS redelivers it after its visibility. */
    default void messageActionFailed(String queue, String messageId, Throwable cause) {
    }

    /** A receive call failed; the loop continues after the given wait. */
    default void receiveFailed(String queue, Throwable cause, Duration nextAttemptIn) {
    }

    /** The provisioning heartbeat stopped because there was no progress within the configured window. */
    default void heartbeatStopped(String queue, String messageId) {
    }
}
