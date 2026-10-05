package com.nequi.ticketing.infrastructure.observability;

import com.nequi.ticketing.application.port.in.DispositionReason;
import com.nequi.ticketing.application.port.in.MessageDisposition;
import com.nequi.ticketing.application.port.in.ProvisionEventCommand;
import com.nequi.ticketing.application.port.in.ProvisionEventUseCase;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Mono;

/**
 * Provision Event (CMP-022) inside the span {@code ticketing.event.provision}, child of the trace of the Event
 * creation (ADR-037), counting each result and recording the duration of the message run that enabled the Event
 * (aws-target §7 "aprovisionamientos ... y su duración").
 */
final class ObservedProvisionEvent implements ProvisionEventUseCase {

    static final String QUEUE = "provisioning";

    private final ProvisionEventUseCase delegate;
    private final Telemetry telemetry;

    ObservedProvisionEvent(ProvisionEventUseCase delegate, Telemetry telemetry) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.telemetry = telemetry;
    }

    @Override
    public Mono<MessageDisposition> provision(ProvisionEventCommand command) {
        Mono<MessageDisposition> timed = Mono.defer(() -> {
            long start = System.nanoTime();
            return delegate.provision(command).doOnNext(disposition -> {
                Dispositions.count(telemetry, QUEUE, disposition);
                if (disposition.reason() == DispositionReason.ENABLED) {
                    telemetry.timer(MetricNames.PROVISIONING_DURATION)
                            .record(Duration.ofNanos(System.nanoTime() - start));
                }
            });
        });
        return telemetry.spans().child("ticketing.event.provision",
                Map.of("eventId", String.valueOf(command.eventId())), timed);
    }
}
