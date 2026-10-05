package com.nequi.ticketing.infrastructure.observability;

import com.nequi.ticketing.application.port.out.AuthorizationOutcome;
import com.nequi.ticketing.application.port.out.CancellationOutcome;
import com.nequi.ticketing.application.port.out.PaymentAuthorization;
import com.nequi.ticketing.application.port.out.PaymentGateway;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import reactor.core.publisher.Mono;

/**
 * Payment latency by operation and outcome (aws-target §7 "latencia del pago") and the payment spans of the trace
 * of the Order (ADR-037). The outcome is the typed result of the adapter; the adapter never emits an error, so an
 * error or an empty result is reported as {@code unavailable} and passed through unchanged.
 */
final class ObservedPaymentGateway implements PaymentGateway {

    private final PaymentGateway delegate;
    private final Telemetry telemetry;

    ObservedPaymentGateway(PaymentGateway delegate, Telemetry telemetry) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.telemetry = telemetry;
    }

    @Override
    public Mono<AuthorizationOutcome> authorize(PaymentAuthorization request, Instant deadline) {
        return observe("authorize", request.paymentAttemptId(), delegate.authorize(request, deadline),
                ObservedPaymentGateway::authorization);
    }

    @Override
    public Mono<CancellationOutcome> cancel(String paymentAttemptId) {
        return observe("cancel", paymentAttemptId, delegate.cancel(paymentAttemptId),
                ObservedPaymentGateway::cancellation);
    }

    private <T> Mono<T> observe(String operation, String paymentAttemptId, Mono<T> call, Function<T, String> outcome) {
        Mono<T> timed = Mono.defer(() -> {
            long start = System.nanoTime();
            return call.doOnNext(result -> record(operation, outcome.apply(result), start))
                    .doOnError(error -> record(operation, "unavailable", start))
                    .switchIfEmpty(Mono.defer(() -> {
                        record(operation, "unavailable", start);
                        return Mono.empty();
                    }));
        });
        return telemetry.spans().child("ticketing.payment." + operation,
                Map.of("paymentAttemptId", String.valueOf(paymentAttemptId)), timed);
    }

    private void record(String operation, String outcome, long start) {
        telemetry.timer(MetricNames.PAYMENT_DURATION, "operation", operation, "outcome", outcome)
                .record(Duration.ofNanos(System.nanoTime() - start));
    }

    static String authorization(AuthorizationOutcome outcome) {
        return switch (outcome) {
            case AuthorizationOutcome.Approved approved -> "approved";
            case AuthorizationOutcome.Declined declined -> "declined";
            case AuthorizationOutcome.ContractError error -> "contract_error";
            case AuthorizationOutcome.DependencyUnavailable unavailable -> "unavailable";
        };
    }

    static String cancellation(CancellationOutcome outcome) {
        return switch (outcome) {
            case CancellationOutcome.Cancelled cancelled -> "cancelled";
            case CancellationOutcome.ContractError error -> "contract_error";
            case CancellationOutcome.DependencyUnavailable unavailable -> "unavailable";
        };
    }
}
