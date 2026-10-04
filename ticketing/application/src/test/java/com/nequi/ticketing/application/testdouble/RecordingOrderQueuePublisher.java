package com.nequi.ticketing.application.testdouble;

import com.nequi.ticketing.application.port.out.OrderProcessingRequested;
import com.nequi.ticketing.application.port.out.OrderQueuePublisher;
import com.nequi.ticketing.application.port.out.PublishResult;
import com.nequi.ticketing.application.port.out.PublisherAvailability;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import reactor.core.publisher.Mono;

/**
 * MSG-001 publisher double: records every publication, returns scripted results (default
 * {@code PUBLISHED}) and exposes a settable availability that stands in for the publication circuit.
 */
public final class RecordingOrderQueuePublisher implements OrderQueuePublisher {

    private final List<OrderProcessingRequested> published = new CopyOnWriteArrayList<>();
    private final Deque<PublishResult> scripted = new ArrayDeque<>();
    private volatile PublisherAvailability availability = PublisherAvailability.available();
    private volatile Consumer<OrderProcessingRequested> onPublish = message -> { };

    @Override
    public Mono<PublishResult> publish(OrderProcessingRequested message) {
        return Mono.fromCallable(() -> {
            published.add(message);
            onPublish.accept(message);
            synchronized (scripted) {
                return scripted.isEmpty() ? PublishResult.PUBLISHED : scripted.poll();
            }
        });
    }

    @Override
    public PublisherAvailability availability() {
        return availability;
    }

    public void script(PublishResult... results) {
        synchronized (scripted) {
            scripted.addAll(List.of(results));
        }
    }

    public void setAvailability(PublisherAvailability value) {
        this.availability = value;
    }

    public void onPublish(Consumer<OrderProcessingRequested> hook) {
        this.onPublish = hook;
    }

    public List<OrderProcessingRequested> published() {
        return List.copyOf(published);
    }
}
