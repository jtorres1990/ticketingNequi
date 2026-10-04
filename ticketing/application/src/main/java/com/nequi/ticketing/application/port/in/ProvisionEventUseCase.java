package com.nequi.ticketing.application.port.in;

import reactor.core.publisher.Mono;

/** Inbound port "Provision Event" (CMP-022, MSG-002, {@code ticketing.messaging.v2.md} §5.2). */
public interface ProvisionEventUseCase {

    Mono<MessageDisposition> provision(ProvisionEventCommand command);
}
