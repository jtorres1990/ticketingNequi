package com.nequi.ticketing.infrastructure.adapter.sqs;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** {@link SqsEvents} that records every notification as a short text, for assertions. */
public final class RecordingSqsEvents implements SqsEvents {

    private final List<String> recorded = new CopyOnWriteArrayList<>();
    private final List<Throwable> causes = new CopyOnWriteArrayList<>();
    private final List<Duration> waits = new CopyOnWriteArrayList<>();

    @Override
    public void publicationFailed(String queue, Throwable cause) {
        recorded.add("publicationFailed:" + queue);
        causes.add(cause);
    }

    @Override
    public void unreadableMessage(String queue, String messageId, String reason) {
        recorded.add("unreadable:" + queue + ":" + messageId + ":" + reason);
    }

    @Override
    public void processingFailed(String queue, String messageId, Throwable cause) {
        recorded.add("processingFailed:" + queue + ":" + messageId);
        causes.add(cause);
    }

    @Override
    public void messageActionFailed(String queue, String messageId, Throwable cause) {
        recorded.add("actionFailed:" + queue + ":" + messageId);
        causes.add(cause);
    }

    @Override
    public void receiveFailed(String queue, Throwable cause, Duration nextAttemptIn) {
        recorded.add("receiveFailed:" + queue);
        causes.add(cause);
        waits.add(nextAttemptIn);
    }

    @Override
    public void heartbeatStopped(String queue, String messageId) {
        recorded.add("heartbeatStopped:" + queue + ":" + messageId);
    }

    public List<String> recorded() {
        return List.copyOf(recorded);
    }

    public List<Throwable> causes() {
        return List.copyOf(causes);
    }

    public List<Duration> waits() {
        return List.copyOf(waits);
    }
}
