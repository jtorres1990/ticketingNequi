package com.nequi.ticketing.application.port.in;

import reactor.core.publisher.Mono;

/** Inbound port "Create Event" (CMP-003, API-001). */
public interface CreateEventUseCase {

    Mono<EventCreationResult> createEvent(CreateEventCommand command);
}
