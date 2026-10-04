package com.nequi.ticketing.application.port.in;

import reactor.core.publisher.Mono;

/** Inbound port "Process Order" (CMP-007, MSG-001, {@code ticketing.messaging.v2.md} §5.1). */
public interface ProcessOrderUseCase {

    Mono<MessageDisposition> process(ProcessOrderCommand command);
}
