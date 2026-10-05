package com.nequi.ticketing.infrastructure.observability;

import com.nequi.ticketing.infrastructure.adapter.sqs.SqsEvents;
import java.time.Duration;

/** SQS adapter notifications (CMP-011, CMP-012, CMP-025) as {@code ticketing.sqs.failures} and WARN records. */
final class ObservedSqsEvents implements SqsEvents {

    private final Telemetry telemetry;

    ObservedSqsEvents(Telemetry telemetry) {
        this.telemetry = telemetry;
    }

    @Override
    public void publicationFailed(String queue, Throwable cause) {
        failure(queue, "publication").cause(cause).write();
    }

    @Override
    public void unreadableMessage(String queue, String messageId, String reason) {
        failure(queue, "unreadable").with("messageId", messageId).with("reason", reason).write();
    }

    @Override
    public void processingFailed(String queue, String messageId, Throwable cause) {
        failure(queue, "processing").with("messageId", messageId).cause(cause).write();
    }

    @Override
    public void messageActionFailed(String queue, String messageId, Throwable cause) {
        failure(queue, "message_action").with("messageId", messageId).cause(cause).write();
    }

    @Override
    public void receiveFailed(String queue, Throwable cause, Duration nextAttemptIn) {
        failure(queue, "receive").with("nextAttemptInMs", nextAttemptIn == null ? null : nextAttemptIn.toMillis())
                .cause(cause).write();
    }

    @Override
    public void heartbeatStopped(String queue, String messageId) {
        failure(queue, "heartbeat_stopped").with("messageId", messageId).write();
    }

    private StructuredLog.Entry failure(String queue, String kind) {
        telemetry.counter(MetricNames.SQS_FAILURES, "queue", String.valueOf(queue), "kind", kind).increment();
        return telemetry.log().warn("sqs." + kind, "SQS " + kind.replace('_', ' ') + " failure")
                .with("queue", queue);
    }
}
