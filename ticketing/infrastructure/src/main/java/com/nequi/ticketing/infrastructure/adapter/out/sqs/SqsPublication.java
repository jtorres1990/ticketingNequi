package com.nequi.ticketing.infrastructure.adapter.out.sqs;

import com.nequi.ticketing.application.port.out.Clock;
import com.nequi.ticketing.infrastructure.adapter.sqs.SqsClientFactory;
import com.nequi.ticketing.infrastructure.adapter.sqs.SqsConnectionSettings;
import com.nequi.ticketing.infrastructure.adapter.sqs.SqsEvents;
import java.util.Objects;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

/**
 * The publication client (without SDK retries, IV-004) and the publisher of both queues built on it (CMP-011), as
 * one resource that the composition (CMP-021) opens at startup and closes after the ordered shutdown, so that the
 * publications of the requests still in flight complete first (ADR-037).
 */
public final class SqsPublication implements AutoCloseable {

    private final SqsAsyncClient client;
    private final SqsQueuePublisher publisher;

    private SqsPublication(SqsAsyncClient client, SqsQueuePublisher publisher) {
        this.client = client;
        this.publisher = publisher;
    }

    public static SqsPublication open(SqsConnectionSettings connection, SqsPublisherSettings settings, Clock clock,
            SqsEvents events) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(events, "events");
        SqsAsyncClient client = SqsClientFactory.forPublication(connection);
        return new SqsPublication(client, SqsQueuePublisher.create(client, settings, clock, events));
    }

    public SqsQueuePublisher publisher() {
        return publisher;
    }

    @Override
    public void close() {
        client.close();
    }
}
