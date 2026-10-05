package com.nequi.ticketing.infrastructure.adapter.out.payment;

import com.nequi.ticketing.application.port.out.AuthorizationOutcome;
import com.nequi.ticketing.application.port.out.CancellationOutcome;
import com.nequi.ticketing.application.port.out.Clock;
import com.nequi.ticketing.application.port.out.PaymentAuthorization;
import com.nequi.ticketing.application.port.out.PaymentGateway;
import com.nequi.ticketing.infrastructure.adapter.out.resilience.ManagedCircuitBreaker;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

/**
 * CMP-013 Payment gateway adapter (FR-015, TC-017, BR-020, BR-034; ADR-030, ADR-035, ADR-008): implements
 * the outbound port Payment gateway against the HTTP contract of the Payment Mock (API-101, API-102) with its
 * own wire types (ADR-034 boundary rule 7).
 *
 * <p>Policy, from the inside out (ADR-035): timeout per call, the Payment Mock circuit breaker (CMP-026,
 * shared by authorization and cancellation) and, only for the authorization, retry of transient failures:
 * <ul>
 *   <li>authorization: timeout of 3 s per call, up to 2 retries with backoff and jitter, the same
 *       {@code paymentAttemptId} as {@code Idempotency-Key} (ADR-027), and never beyond the deadline given by
 *       the use case ({@code expiresAt} minus the application margin, ADR-008 point 4). Reaching the deadline
 *       cancels the call in flight (a cancelled call is not recorded by the circuit) and is "dependency
 *       unavailable";</li>
 *   <li>cancellation: a single call with a timeout of 3 s; the persisted reversal schedule is the retry
 *       (ADR-025).</li>
 * </ul>
 * Classification (ADR-030): {@code APPROVED} and {@code DECLINED} (including {@code ATTEMPT_CANCELLED}) are
 * results; any 4xx is a contract error (never retried, ignored by the circuit); timeout, connection, 5xx,
 * an invalid response or the open circuit are "dependency unavailable". The adapter never emits an error
 * signal and never blocks.
 */
public final class PaymentMockGateway implements PaymentGateway {

    static final String CIRCUIT_OPEN = "CIRCUIT_OPEN";
    static final String DEADLINE_REACHED = "DEADLINE_REACHED";

    private final PaymentTransport transport;
    private final PaymentGatewaySettings settings;
    private final Clock clock;
    private final PaymentEvents events;
    private final Scheduler scheduler;
    private final ManagedCircuitBreaker circuit;

    private PaymentMockGateway(PaymentTransport transport, PaymentGatewaySettings settings, Clock clock,
            PaymentEvents events, Scheduler scheduler) {
        this.transport = transport;
        this.settings = settings;
        this.clock = clock;
        this.events = events;
        this.scheduler = scheduler;
        this.circuit = ManagedCircuitBreaker.create("payment-mock", settings.circuit(), clock,
                PaymentCallFailure::countsAsFailure);
    }

    public static PaymentMockGateway create(PaymentGatewaySettings settings, Clock clock, PaymentEvents events) {
        return create(settings, clock, events, Schedulers.parallel());
    }

    /** Variant with an explicit timer scheduler. */
    public static PaymentMockGateway create(PaymentGatewaySettings settings, Clock clock, PaymentEvents events,
            Scheduler scheduler) {
        Objects.requireNonNull(settings, "settings");
        return create(WebClientPaymentTransport.create(settings), settings, clock, events, scheduler);
    }

    /** Variant with an explicit transport (virtual time in tests). */
    static PaymentMockGateway create(PaymentTransport transport, PaymentGatewaySettings settings, Clock clock,
            PaymentEvents events, Scheduler scheduler) {
        return new PaymentMockGateway(
                Objects.requireNonNull(transport, "transport"),
                Objects.requireNonNull(settings, "settings"),
                Objects.requireNonNull(clock, "clock"),
                Objects.requireNonNull(events, "events"),
                Objects.requireNonNull(scheduler, "scheduler"));
    }

    /**
     * The Payment Mock circuit: state, remaining open time and state changes (pause and resume of the Orders
     * loop and of the reversal process, metrics in INC-010).
     */
    public ManagedCircuitBreaker circuit() {
        return circuit;
    }

    @Override
    public Mono<AuthorizationOutcome> authorize(PaymentAuthorization request, Instant deadline) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(deadline, "deadline");
        String paymentAttemptId = request.paymentAttemptId();
        return Mono.defer(() -> {
            Duration remaining = Duration.between(clock.now(), deadline);
            if (remaining.isNegative() || remaining.isZero()) {
                return Mono.just(authorizationUnavailable(paymentAttemptId, DEADLINE_REACHED));
            }
            String body = PaymentWireFormat.authorizationRequest(request);
            Mono<AuthorizationOutcome> attempt = Mono.defer(() -> transport.authorize(paymentAttemptId, body))
                    .timeout(settings.authorizationTimeout(), scheduler)
                    .map(reply -> PaymentWireFormat.authorizationOutcome(paymentAttemptId, reply))
                    .onErrorMap(PaymentCallFailure::classify)
                    .doOnError(error -> events.callFailed(PaymentEvents.AUTHORIZE, paymentAttemptId,
                            ((PaymentCallFailure) error).reason()))
                    .transform(circuit.operator());
            return attempt
                    .retryWhen(Retry.backoff(settings.authorizationRetries(), settings.retryBackoffBase())
                            .maxBackoff(settings.retryBackoffMax())
                            .jitter(settings.retryJitter())
                            .scheduler(scheduler)
                            .filter(error -> error instanceof PaymentCallFailure.Transient)
                            .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                    .take(remaining, scheduler)
                    .switchIfEmpty(Mono.fromSupplier(() -> authorizationUnavailable(paymentAttemptId, DEADLINE_REACHED)))
                    .onErrorResume(error -> Mono.just(authorizationFailure(paymentAttemptId, error)));
        });
    }

    @Override
    public Mono<CancellationOutcome> cancel(String paymentAttemptId) {
        Objects.requireNonNull(paymentAttemptId, "paymentAttemptId");
        return Mono.defer(() -> transport.cancel(paymentAttemptId))
                .timeout(settings.cancellationTimeout(), scheduler)
                .map(reply -> PaymentWireFormat.cancellationOutcome(paymentAttemptId, reply))
                .onErrorMap(PaymentCallFailure::classify)
                .doOnError(error -> events.callFailed(PaymentEvents.CANCEL, paymentAttemptId,
                        ((PaymentCallFailure) error).reason()))
                .transform(circuit.operator())
                .onErrorResume(error -> Mono.just(cancellationFailure(paymentAttemptId, error)));
    }

    private AuthorizationOutcome authorizationFailure(String paymentAttemptId, Throwable error) {
        if (error instanceof PaymentCallFailure.Contract contract) {
            return new AuthorizationOutcome.ContractError(contract.status());
        }
        return authorizationUnavailable(paymentAttemptId, unavailableReason(error));
    }

    private AuthorizationOutcome authorizationUnavailable(String paymentAttemptId, String reason) {
        events.dependencyUnavailable(PaymentEvents.AUTHORIZE, paymentAttemptId, reason);
        return new AuthorizationOutcome.DependencyUnavailable(reason);
    }

    private CancellationOutcome cancellationFailure(String paymentAttemptId, Throwable error) {
        if (error instanceof PaymentCallFailure.Contract contract) {
            return new CancellationOutcome.ContractError(contract.status());
        }
        String reason = unavailableReason(error);
        events.dependencyUnavailable(PaymentEvents.CANCEL, paymentAttemptId, reason);
        return new CancellationOutcome.DependencyUnavailable(reason);
    }

    private static String unavailableReason(Throwable error) {
        if (ManagedCircuitBreaker.isRejection(error)) {
            return CIRCUIT_OPEN;
        }
        return PaymentCallFailure.classify(error).reason();
    }
}
