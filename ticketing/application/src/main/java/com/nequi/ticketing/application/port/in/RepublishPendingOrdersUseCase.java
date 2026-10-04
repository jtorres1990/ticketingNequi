package com.nequi.ticketing.application.port.in;

import reactor.core.publisher.Mono;

/** Inbound port "Republish pending Orders" (CMP-023, ADR-026), triggered every 10 s by CMP-014. */
public interface RepublishPendingOrdersUseCase {

    Mono<CycleResult> republishPending(CycleRequest request);
}
