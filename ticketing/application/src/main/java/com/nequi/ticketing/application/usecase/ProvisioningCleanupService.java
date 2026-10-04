package com.nequi.ticketing.application.usecase;

import com.nequi.ticketing.application.port.in.CleanUpProvisioningUseCase;
import com.nequi.ticketing.application.port.in.CycleRequest;
import com.nequi.ticketing.application.port.in.CycleResult;
import com.nequi.ticketing.application.port.in.ItemOutcome;
import com.nequi.ticketing.application.port.out.Clock;
import com.nequi.ticketing.application.port.out.EventCatalog;
import com.nequi.ticketing.application.port.out.EventProvisioningRequested;
import com.nequi.ticketing.application.port.out.ProvisioningFailurePlan;
import com.nequi.ticketing.application.port.out.ProvisioningQueuePublisher;
import com.nequi.ticketing.application.port.out.ProvisioningSnapshot;
import com.nequi.ticketing.application.port.out.PublishResult;
import com.nequi.ticketing.application.port.out.StalledProvisioning;
import com.nequi.ticketing.application.port.out.TicketInventory;
import com.nequi.ticketing.application.port.out.TransactionOutcome;
import com.nequi.ticketing.domain.audit.AuditRecord.Actor;
import com.nequi.ticketing.domain.audit.AuditRecord.ActorType;
import com.nequi.ticketing.domain.event.Event;
import com.nequi.ticketing.domain.event.Event.ProvisioningStatus;
import com.nequi.ticketing.domain.event.InventoryDefinition.TicketSeed;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * CMP-015 Provisioning cleanup (FR-001, FR-003, ERR-018; ADR-024 section 5):
 * <ul>
 *   <li>stalled detection: Events in {@code PROVISIONING} without progress for more than 3 minutes
 *       (AP-018, confirmed by a strongly consistent read) are republished (AP-033 then MSG-002) while they
 *       were republished fewer than 3 times, and marked {@code FAILED} (AP-026) otherwise;</li>
 *   <li>purge: the Tickets of {@code FAILED} Events are deleted by their generated keys and the Event is
 *       marked {@code ticketsPurgedAt} (AP-027); the Event itself stays consultable by the {@code ADMIN}.</li>
 * </ul>
 */
public final class ProvisioningCleanupService implements CleanUpProvisioningUseCase {

    private final EventCatalog eventCatalog;
    private final TicketInventory ticketInventory;
    private final ProvisioningQueuePublisher provisioningPublisher;
    private final Clock clock;
    private final WorkerUseCaseSettings settings;
    private final Actor actor;

    public ProvisioningCleanupService(
            EventCatalog eventCatalog,
            TicketInventory ticketInventory,
            ProvisioningQueuePublisher provisioningPublisher,
            Clock clock,
            WorkerUseCaseSettings settings) {
        this.eventCatalog = Objects.requireNonNull(eventCatalog, "eventCatalog");
        this.ticketInventory = Objects.requireNonNull(ticketInventory, "ticketInventory");
        this.provisioningPublisher = Objects.requireNonNull(provisioningPublisher, "provisioningPublisher");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.actor = new Actor(ActorType.WORKER, settings.workerId());
    }

    @Override
    public Mono<CycleResult> cleanUp(CycleRequest request) {
        return Mono.defer(() -> {
            Objects.requireNonNull(request, "request");
            Instant progressBefore = clock.now().minus(settings.stalledProvisioningThreshold());
            Flux<ItemOutcome> stalled = CycleSupport.process(
                    CycleSupport.guarded(eventCatalog.findStalledProvisioning(progressBefore)
                            .map(StalledProvisioning::eventId)),
                    eventId -> handleStalled(eventId, request.correlationId()), settings.cleanupConcurrency());
            Flux<ItemOutcome> purged = CycleSupport.process(
                    CycleSupport.guarded(eventCatalog.findFailedPendingPurge()),
                    this::purge, settings.cleanupConcurrency());
            return CycleSupport.summarize(Flux.concat(stalled, purged));
        });
    }

    private Mono<ItemOutcome> handleStalled(String eventId, String correlationId) {
        return eventCatalog.findProvisioningSnapshot(eventId).flatMap(snapshot -> {
            Instant now = clock.now();
            if (snapshot.event().provisioningStatus() != ProvisioningStatus.PROVISIONING
                    || !snapshot.progressReference().isBefore(now.minus(settings.stalledProvisioningThreshold()))) {
                return Mono.just(ItemOutcome.NOT_APPLICABLE);
            }
            return snapshot.republishCount() < settings.maximumProvisioningRepublications()
                    ? republish(snapshot, correlationId, now)
                    : markFailed(snapshot.event(), correlationId, now);
        });
    }

    private Mono<ItemOutcome> republish(ProvisioningSnapshot snapshot, String correlationId, Instant now) {
        String eventId = snapshot.event().eventId();
        return eventCatalog.registerRepublication(eventId, snapshot.progressReference(), now)
                .defaultIfEmpty(Boolean.FALSE)
                .flatMap(registered -> registered
                        ? provisioningPublisher.publish(new EventProvisioningRequested(eventId, correlationId))
                                .defaultIfEmpty(PublishResult.FAILED)
                                .onErrorReturn(PublishResult.FAILED)
                                .map(result -> result == PublishResult.PUBLISHED
                                        ? ItemOutcome.PROVISIONING_REPUBLISHED
                                        : ItemOutcome.FAILED)
                        : Mono.just(ItemOutcome.NOT_APPLICABLE));
    }

    private Mono<ItemOutcome> markFailed(Event event, String correlationId, Instant now) {
        Event failed = event.fail();
        ProvisioningFailurePlan plan = new ProvisioningFailurePlan(event, failed,
                WorkerAudits.eventProvisioningFailed(failed, actor, correlationId, now), now);
        return eventCatalog.markFailed(plan).map(outcome -> switch (outcome) {
            case TransactionOutcome.Applied applied -> ItemOutcome.PROVISIONING_FAILED;
            case TransactionOutcome.Cancelled cancelled -> ItemOutcome.NOT_APPLICABLE;
            case TransactionOutcome.Conflict conflict -> ItemOutcome.FAILED;
        });
    }

    private Mono<ItemOutcome> purge(String eventId) {
        return eventCatalog.findProvisioningSnapshot(eventId).flatMap(snapshot -> {
            Event event = snapshot.event();
            if (event.provisioningStatus() != ProvisioningStatus.FAILED || snapshot.ticketsPurgedAt() != null) {
                return Mono.just(ItemOutcome.NOT_APPLICABLE);
            }
            List<String> ticketIds = event.inventoryDefinition().validate(event.capacity(), settings.inventoryLimits())
                    .tickets().stream().map(TicketSeed::ticketId).toList();
            return ticketInventory.purge(event, ticketIds)
                    .then(Mono.defer(() -> eventCatalog.markTicketsPurged(eventId, clock.now())))
                    .defaultIfEmpty(Boolean.FALSE)
                    .map(marked -> marked ? ItemOutcome.TICKETS_PURGED : ItemOutcome.NOT_APPLICABLE);
        });
    }
}
