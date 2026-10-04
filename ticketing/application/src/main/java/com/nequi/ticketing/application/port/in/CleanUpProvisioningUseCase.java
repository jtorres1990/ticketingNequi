package com.nequi.ticketing.application.port.in;

import reactor.core.publisher.Mono;

/** Inbound port "Clean up provisioning" (CMP-015, ADR-024), triggered every 60 s by CMP-014. */
public interface CleanUpProvisioningUseCase {

    Mono<CycleResult> cleanUp(CycleRequest request);
}
