package com.nequi.ticketing.application.port.in;

import reactor.core.publisher.Mono;

/** Inbound port "Reverse payments" (CMP-024, ADR-025), triggered every 10 s by CMP-014. */
public interface ReversePaymentsUseCase {

    Mono<CycleResult> reverseDue(CycleRequest request);
}
