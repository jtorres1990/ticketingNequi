package com.nequi.ticketing.application.port.in;

import reactor.core.publisher.Mono;

/** Inbound port "Start purchase" (CMP-005, API-004). */
public interface StartPurchaseUseCase {

    Mono<PurchaseResult> startPurchase(StartPurchaseCommand command);
}
