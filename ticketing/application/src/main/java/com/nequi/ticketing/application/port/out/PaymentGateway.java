package com.nequi.ticketing.application.port.out;

import java.time.Instant;
import reactor.core.publisher.Mono;

/**
 * Outbound port "Payment gateway" (ADR-034, ADR-030): authorize and cancel a PaymentAttempt with typed
 * results, including "dependency unavailable". Timeouts, retries of transient failures and the circuit
 * breaker live in the adapter (ADR-035); the use case only receives the classified result.
 */
public interface PaymentGateway {

    /**
     * API-101 with {@code Idempotency-Key = paymentAttemptId}. The adapter never waits beyond
     * {@code deadline} ({@code expiresAt} minus the application margin, ADR-008 point 4); reaching it is
     * reported as {@link AuthorizationOutcome.DependencyUnavailable}.
     */
    Mono<AuthorizationOutcome> authorize(PaymentAuthorization request, Instant deadline);

    /** API-102: idempotent cancellation of the PaymentAttempt, valid in any state of the attempt (BR-034). */
    Mono<CancellationOutcome> cancel(String paymentAttemptId);
}
