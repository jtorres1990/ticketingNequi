package com.nequi.ticketing.application.testdouble;

import com.nequi.ticketing.application.port.out.EventProvisioningRequested;
import com.nequi.ticketing.application.port.out.ProvisioningQueuePublisher;
import com.nequi.ticketing.application.port.out.PublishResult;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import reactor.core.publisher.Mono;

/** MSG-002 publisher double: records publications and returns a settable result. */
public final class RecordingProvisioningQueuePublisher implements ProvisioningQueuePublisher {

    private final List<EventProvisioningRequested> published = new CopyOnWriteArrayList<>();
    private volatile PublishResult result = PublishResult.PUBLISHED;

    @Override
    public Mono<PublishResult> publish(EventProvisioningRequested message) {
        return Mono.fromCallable(() -> {
            published.add(message);
            return result;
        });
    }

    public void setResult(PublishResult value) {
        this.result = value;
    }

    public List<EventProvisioningRequested> published() {
        return List.copyOf(published);
    }
}
