package com.nequi.ticketing.application.port.in;

import reactor.core.publisher.Mono;

/** Inbound port "List Events" (CMP-004, API-002). */
public interface ListEventsUseCase {

    Mono<EventSummaryPage> listEvents(ListEventsQuery query);
}
