package com.nequi.ticketing.application.port.out;

import reactor.core.publisher.Mono;

/** Outbound port: publication of MSG-001 on the Orders queue (ADR-026, ADR-035). */
public interface OrderQueuePublisher {

    /** Publishes with the bounded policy of the adapter; a failed publication is a {@code FAILED} result. */
    Mono<PublishResult> publish(OrderProcessingRequested message);

    /** Whether publication is currently possible, checked before reserving (ADR-035). */
    PublisherAvailability availability();
}
