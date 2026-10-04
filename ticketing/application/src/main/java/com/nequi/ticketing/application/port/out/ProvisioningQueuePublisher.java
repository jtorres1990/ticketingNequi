package com.nequi.ticketing.application.port.out;

import reactor.core.publisher.Mono;

/** Outbound port: publication of MSG-002 on the provisioning queue (ADR-024, ADR-035). */
public interface ProvisioningQueuePublisher {

    /** Publishes with the bounded policy of the adapter; a failed publication is a {@code FAILED} result. */
    Mono<PublishResult> publish(EventProvisioningRequested message);
}
