package com.nequi.ticketing.infrastructure.observability;

import com.nequi.ticketing.application.port.in.AvailabilityQuery;
import com.nequi.ticketing.application.port.in.AvailabilityView;
import com.nequi.ticketing.application.port.in.GetEventAvailabilityUseCase;
import java.util.Objects;
import reactor.core.publisher.Mono;

/** Counts the availability queries (API-003): the denominator of the hit ratio of the 1 s count cache. */
final class ObservedAvailability implements GetEventAvailabilityUseCase {

    private final GetEventAvailabilityUseCase delegate;
    private final Telemetry telemetry;

    ObservedAvailability(GetEventAvailabilityUseCase delegate, Telemetry telemetry) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.telemetry = telemetry;
    }

    @Override
    public Mono<AvailabilityView> getAvailability(AvailabilityQuery query) {
        return Mono.defer(() -> {
            telemetry.counter(MetricNames.AVAILABILITY_REQUESTS).increment();
            return delegate.getAvailability(query);
        });
    }
}
