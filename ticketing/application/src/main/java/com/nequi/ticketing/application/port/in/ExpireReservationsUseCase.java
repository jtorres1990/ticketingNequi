package com.nequi.ticketing.application.port.in;

import reactor.core.publisher.Mono;

/** Inbound port "Expire Reservations" (CMP-008, ADR-028), triggered every 5 s by CMP-014. */
public interface ExpireReservationsUseCase {

    Mono<CycleResult> expireDue(CycleRequest request);
}
