package com.nequi.ticketing.application.port.in;

import reactor.core.publisher.Mono;

/**
 * Inbound port "Get Order" (CMP-006, API-005). A non-existent Order and an Order of another customer
 * produce the same {@code ORDER_NOT_FOUND} result (BR-023).
 */
public interface GetOrderUseCase {

    Mono<OrderView> getOrder(GetOrderQuery query);
}
