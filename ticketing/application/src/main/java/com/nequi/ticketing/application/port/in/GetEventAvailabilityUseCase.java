package com.nequi.ticketing.application.port.in;

import reactor.core.publisher.Mono;

/** Inbound port "Get Event availability" (CMP-004, API-003). Read only: it never reserves (BR-017). */
public interface GetEventAvailabilityUseCase {

    Mono<AvailabilityView> getAvailability(AvailabilityQuery query);
}
