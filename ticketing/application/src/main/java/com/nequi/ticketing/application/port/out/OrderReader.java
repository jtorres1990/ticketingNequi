package com.nequi.ticketing.application.port.out;

import reactor.core.publisher.Mono;

/** Outbound port: Order reads (ADR-034). Worker queries are added with their use cases. */
public interface OrderReader {

    /** AP-010: strongly consistent read of the Order item alone; empty when it does not exist. */
    Mono<OrderRecord> findById(String orderId);
}
