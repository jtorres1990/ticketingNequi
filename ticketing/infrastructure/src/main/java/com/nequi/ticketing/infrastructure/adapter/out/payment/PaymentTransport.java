package com.nequi.ticketing.infrastructure.adapter.out.payment;

import reactor.core.publisher.Mono;

/**
 * HTTP exchange with the Payment Mock ({@code payment-mock.openapi.v1.yaml}): it sends one request and
 * returns the status and body of the response, whatever the status; timeouts, the circuit breaker, retries
 * and the classification are applied by {@link PaymentMockGateway}. Implementations never block.
 */
interface PaymentTransport {

    /** API-101 {@code POST /payments} with {@code Idempotency-Key = paymentAttemptId} and the JSON body. */
    Mono<HttpReply> authorize(String paymentAttemptId, String jsonBody);

    /** API-102 {@code POST /payments/{paymentAttemptId}/cancellation}. */
    Mono<HttpReply> cancel(String paymentAttemptId);

    /** Status and body (empty when absent) of one response. */
    record HttpReply(int status, String body) {
    }
}
