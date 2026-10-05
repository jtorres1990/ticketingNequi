package com.nequi.ticketing.infrastructure.observability;

import com.nequi.ticketing.application.port.out.EnabledEventPage;
import com.nequi.ticketing.application.port.out.EnablementPlan;
import com.nequi.ticketing.application.port.out.EventCatalog;
import com.nequi.ticketing.application.port.out.NewEventPlan;
import com.nequi.ticketing.application.port.out.ProvisioningFailurePlan;
import com.nequi.ticketing.application.port.out.ProvisioningSnapshot;
import com.nequi.ticketing.application.port.out.StalledProvisioning;
import com.nequi.ticketing.application.port.out.TransactionOutcome;
import com.nequi.ticketing.domain.event.Event;
import java.time.Instant;
import java.util.Objects;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Provisioning metrics derived from the writes the catalog applied (ADR-024, ADR-037 alarm catalog
 * "aprovisionamientos fallidos > 0"): provisioning started (Event created), enabled, failed and republished.
 */
final class ObservedEventCatalog implements EventCatalog {

    private final EventCatalog delegate;
    private final Telemetry telemetry;

    ObservedEventCatalog(EventCatalog delegate, Telemetry telemetry) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.telemetry = telemetry;
    }

    @Override
    public Mono<TransactionOutcome> create(NewEventPlan plan) {
        return delegate.create(plan).doOnNext(outcome -> {
            if (outcome instanceof TransactionOutcome.Applied) {
                provisioning("started");
                telemetry.log().info("provisioning.started", "Event created, provisioning requested")
                        .with("eventId", plan.event().eventId()).with("capacity", plan.event().capacity())
                        .with("correlationId", plan.audit().correlationId()).write();
            }
        });
    }

    @Override
    public Mono<Event> findEvent(String eventId) {
        return delegate.findEvent(eventId);
    }

    @Override
    public Mono<ProvisioningSnapshot> findProvisioningSnapshot(String eventId) {
        return delegate.findProvisioningSnapshot(eventId);
    }

    @Override
    public Mono<EnabledEventPage> listEnabledUpcoming(Instant now, int limit, String cursor) {
        return delegate.listEnabledUpcoming(now, limit, cursor);
    }

    @Override
    public Mono<Boolean> acquireProvisioningLease(String eventId, String owner, Instant leaseUntil, Instant now) {
        return delegate.acquireProvisioningLease(eventId, owner, leaseUntil, now);
    }

    @Override
    public Mono<Boolean> recordProvisioningProgress(String eventId, String owner, Instant leaseUntil,
            int provisionedBatches, Instant now) {
        return delegate.recordProvisioningProgress(eventId, owner, leaseUntil, provisionedBatches, now);
    }

    @Override
    public Mono<TransactionOutcome> enable(EnablementPlan plan) {
        return delegate.enable(plan).doOnNext(outcome -> {
            if (outcome instanceof TransactionOutcome.Applied) {
                provisioning("enabled");
                telemetry.log().info("provisioning.enabled", "Event enabled after verifying its Tickets")
                        .with("eventId", plan.enabled().eventId()).with("capacity", plan.enabled().capacity())
                        .write();
            }
        });
    }

    @Override
    public Mono<TransactionOutcome> markFailed(ProvisioningFailurePlan plan) {
        return delegate.markFailed(plan).doOnNext(outcome -> {
            if (outcome instanceof TransactionOutcome.Applied) {
                provisioning("failed");
                telemetry.log().warn("provisioning.failed", "Event provisioning failed")
                        .with("eventId", plan.failed().eventId()).write();
            }
        });
    }

    @Override
    public Flux<StalledProvisioning> findStalledProvisioning(Instant progressBefore) {
        return delegate.findStalledProvisioning(progressBefore);
    }

    @Override
    public Mono<Boolean> registerRepublication(String eventId, Instant expectedLastProgressAt, Instant now) {
        return delegate.registerRepublication(eventId, expectedLastProgressAt, now).doOnNext(registered -> {
            if (Boolean.TRUE.equals(registered)) {
                provisioning("republished");
            }
        });
    }

    @Override
    public Flux<String> findFailedPendingPurge() {
        return delegate.findFailedPendingPurge();
    }

    @Override
    public Mono<Boolean> markTicketsPurged(String eventId, Instant purgedAt) {
        return delegate.markTicketsPurged(eventId, purgedAt);
    }

    private void provisioning(String outcome) {
        telemetry.counter(MetricNames.PROVISIONING, "outcome", outcome).increment();
    }
}
