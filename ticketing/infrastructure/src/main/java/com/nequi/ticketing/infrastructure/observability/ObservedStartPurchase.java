package com.nequi.ticketing.infrastructure.observability;

import com.nequi.ticketing.application.error.DependencyUnavailableException;
import com.nequi.ticketing.application.error.RequestRejectedException;
import com.nequi.ticketing.application.port.in.PurchaseResult;
import com.nequi.ticketing.application.port.in.StartPurchaseCommand;
import com.nequi.ticketing.application.port.in.StartPurchaseUseCase;
import com.nequi.ticketing.domain.error.DomainException;
import java.util.Objects;
import reactor.core.publisher.Mono;

/**
 * Reservations created and rejected by code (aws-target §7 "reservas creadas y rechazadas por código"): a new
 * Order is {@code created}, an idempotent repetition is {@code replayed}, a rejection carries its error code
 * ({@code TICKETS_UNAVAILABLE}, {@code ACTIVE_ORDER_EXISTS}, {@code SERVICE_UNAVAILABLE}, ...).
 */
final class ObservedStartPurchase implements StartPurchaseUseCase {

    private final StartPurchaseUseCase delegate;
    private final Telemetry telemetry;

    ObservedStartPurchase(StartPurchaseUseCase delegate, Telemetry telemetry) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.telemetry = telemetry;
    }

    @Override
    public Mono<PurchaseResult> startPurchase(StartPurchaseCommand command) {
        return delegate.startPurchase(command)
                .doOnNext(result -> count(result.replayed() ? "replayed" : "created", "none"))
                .doOnError(error -> count("rejected", code(error)));
    }

    private void count(String outcome, String code) {
        telemetry.counter(MetricNames.RESERVATIONS, "outcome", outcome, "code", code).increment();
    }

    static String code(Throwable error) {
        if (error instanceof RequestRejectedException rejected) {
            return rejected.code().name();
        }
        if (error instanceof DomainException domain) {
            return domain.code().name();
        }
        if (error instanceof DependencyUnavailableException) {
            return "SERVICE_UNAVAILABLE";
        }
        return "INTERNAL_ERROR";
    }
}
