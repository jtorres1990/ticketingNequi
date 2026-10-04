package com.nequi.ticketing.application.usecase;

import com.nequi.ticketing.application.error.RequestRejectedException;
import com.nequi.ticketing.application.port.in.CreateEventCommand;
import com.nequi.ticketing.application.port.in.CreateEventUseCase;
import com.nequi.ticketing.application.port.in.EventCreationResult;
import com.nequi.ticketing.application.port.in.GetEventProvisioningStatusUseCase;
import com.nequi.ticketing.application.port.in.ProvisioningStatusView;
import com.nequi.ticketing.application.port.out.Clock;
import com.nequi.ticketing.application.port.out.EventCatalog;
import com.nequi.ticketing.application.port.out.EventProvisioningRequested;
import com.nequi.ticketing.application.port.out.IdGenerator;
import com.nequi.ticketing.application.port.out.IdempotencyRecord;
import com.nequi.ticketing.application.port.out.IdempotencyStore;
import com.nequi.ticketing.application.port.out.NewEventPlan;
import com.nequi.ticketing.application.port.out.ProvisioningQueuePublisher;
import com.nequi.ticketing.application.port.out.ProvisioningSnapshot;
import com.nequi.ticketing.application.port.out.PublishResult;
import com.nequi.ticketing.application.port.out.TransactionOutcome;
import com.nequi.ticketing.domain.event.Event;
import com.nequi.ticketing.domain.event.Event.ProvisioningStatus;
import com.nequi.ticketing.domain.shared.ContentHash;
import com.nequi.ticketing.domain.shared.DomainChecks;
import com.nequi.ticketing.domain.shared.IdempotencyKey;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import reactor.core.publisher.Mono;

/**
 * CMP-003 Event management (FR-001, FR-017, FR-020, BR-032, VAL-006..VAL-009, VAL-013, VAL-016).
 * Creation: syntactic validation, idempotency lookup before business validation (AP-022), complete
 * validation, atomic creation in {@code PROVISIONING} (AP-001) and MSG-002 publication whose failure
 * does not change the 202 response (ADR-024, ADR-027). Also answers the provisioning status (AP-023).
 */
public final class EventManagementService implements CreateEventUseCase, GetEventProvisioningStatusUseCase {

    private final EventCatalog eventCatalog;
    private final IdempotencyStore idempotencyStore;
    private final ProvisioningQueuePublisher provisioningPublisher;
    private final Clock clock;
    private final IdGenerator idGenerator;
    private final ApiUseCaseSettings settings;

    public EventManagementService(
            EventCatalog eventCatalog,
            IdempotencyStore idempotencyStore,
            ProvisioningQueuePublisher provisioningPublisher,
            Clock clock,
            IdGenerator idGenerator,
            ApiUseCaseSettings settings) {
        this.eventCatalog = Objects.requireNonNull(eventCatalog, "eventCatalog");
        this.idempotencyStore = Objects.requireNonNull(idempotencyStore, "idempotencyStore");
        this.provisioningPublisher = Objects.requireNonNull(provisioningPublisher, "provisioningPublisher");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override
    public Mono<EventCreationResult> createEvent(CreateEventCommand command) {
        return Mono.defer(() -> {
            Objects.requireNonNull(command, "command");
            String adminSubject = DomainChecks.required(command.adminSubject(), "adminSubject");
            String key = IdempotencyKey.validate(command.idempotencyKey());
            String correlationId = Objects.requireNonNull(command.correlationId(), "correlationId");
            String requestHash = ContentHash.event(
                    command.name(), command.venue(), command.startsAt(), command.capacity(), command.inventory());
            Creation creation = new Creation(command, adminSubject, key, requestHash, correlationId);
            return idempotencyStore.findEventCreation(adminSubject, key)
                    .map(Optional::of)
                    .defaultIfEmpty(Optional.empty())
                    .flatMap(existing -> existing
                            .map(record -> replay(record, requestHash))
                            .orElseGet(() -> create(creation)));
        });
    }

    @Override
    public Mono<ProvisioningStatusView> getProvisioningStatus(String eventId) {
        return Mono.defer(() -> eventCatalog.findProvisioningSnapshot(DomainChecks.required(eventId, "eventId"))
                .map(this::view)
                .switchIfEmpty(Mono.error(RequestRejectedException::eventNotFound)));
    }

    private Mono<EventCreationResult> create(Creation creation) {
        CreateEventCommand command = creation.command();
        Instant now = clock.now();
        Event event = Event.create(
                idGenerator.newEventId(),
                command.name(),
                command.venue(),
                command.startsAt(),
                command.capacity(),
                command.inventory(),
                now,
                settings.inventoryLimits());
        int complimentary = event.inventoryDefinition().complimentarySeatCount();
        int totalBatches = Math.ceilDiv(event.capacity(), settings.inventoryLimits().provisioningBatchSize());
        IdempotencyRecord idempotency = new IdempotencyRecord(
                creation.adminSubject(),
                creation.idempotencyKey(),
                event.eventId(),
                creation.requestHash(),
                now,
                now.plus(settings.idempotencyRetention()));
        NewEventPlan plan = new NewEventPlan(
                event,
                idempotency,
                ApiAudits.eventProvisioningRequested(event, creation.adminSubject(), creation.correlationId(), now),
                now,
                creation.adminSubject(),
                event.capacity() - complimentary,
                complimentary,
                totalBatches);
        return eventCatalog.create(plan).flatMap(outcome -> outcome instanceof TransactionOutcome.Applied
                ? requestProvisioning(event, creation.correlationId())
                        .thenReturn(new EventCreationResult(view(new ProvisioningSnapshot(event, now, 0, null, null)), false))
                : resolveRejectedCreation(creation));
    }

    /**
     * ADR-024: a failed MSG-002 publication leaves the Event in PROVISIONING for the stalled-provisioning
     * detection; the response does not change.
     */
    private Mono<PublishResult> requestProvisioning(Event event, String correlationId) {
        return provisioningPublisher.publish(new EventProvisioningRequested(event.eventId(), correlationId))
                .onErrorReturn(PublishResult.FAILED)
                .defaultIfEmpty(PublishResult.FAILED);
    }

    /** ADR-027: a cancelled creation is resolved by re-reading the idempotency record first. */
    private Mono<EventCreationResult> resolveRejectedCreation(Creation creation) {
        return idempotencyStore.findEventCreation(creation.adminSubject(), creation.idempotencyKey())
                .flatMap(record -> replay(record, creation.requestHash()))
                .switchIfEmpty(Mono.error(() -> RequestRejectedException.serviceUnavailable(settings.conflictRetryAfter())));
    }

    /** BR-032: same content returns the same Event with its current status; different content is rejected. */
    private Mono<EventCreationResult> replay(IdempotencyRecord record, String requestHash) {
        if (!record.sameContent(requestHash)) {
            return Mono.error(RequestRejectedException.idempotencyKeyReused());
        }
        return eventCatalog.findProvisioningSnapshot(record.resourceId())
                .switchIfEmpty(Mono.error(() -> new IllegalStateException("idempotency record without its Event")))
                .map(snapshot -> new EventCreationResult(view(snapshot), true));
    }

    private ProvisioningStatusView view(ProvisioningSnapshot snapshot) {
        Event event = snapshot.event();
        ProvisioningStatus status = event.provisioningStatus();
        long written = (long) snapshot.provisionedBatches() * settings.inventoryLimits().provisioningBatchSize();
        int provisioned = status == ProvisioningStatus.ENABLED
                ? event.capacity()
                : (int) Math.min(event.capacity(), written);
        return new ProvisioningStatusView(
                event.eventId(),
                status,
                event.capacity(),
                event.inventoryDefinition().complimentarySeatCount(),
                provisioned,
                snapshot.createdAt(),
                snapshot.enabledAt(),
                snapshot.failedAt(),
                status == ProvisioningStatus.FAILED ? ProvisioningStatusView.FailureCause.PROVISIONING_FAILED : null);
    }

    private record Creation(
            CreateEventCommand command,
            String adminSubject,
            String idempotencyKey,
            String requestHash,
            String correlationId) {
    }
}
