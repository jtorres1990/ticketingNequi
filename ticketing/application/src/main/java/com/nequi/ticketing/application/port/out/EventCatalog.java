package com.nequi.ticketing.application.port.out;

import com.nequi.ticketing.domain.event.Event;
import java.time.Instant;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Outbound port: Event catalog (ADR-034): creation with idempotency, reads, listing, provisioning lease
 * and progress, enablement, {@code FAILED}, stalled detection, republication and purge marker. A
 * condition that is not met is a {@link TransactionOutcome.Cancelled} result or {@code false}, never an
 * error.
 */
public interface EventCatalog {

    /** AP-001: atomic creation; cancellation reasons per item (ADR-027 race resolution). */
    Mono<TransactionOutcome> create(NewEventPlan plan);

    /**
     * AP-006: Event metadata and definition by identifier, in any provisioning status; eventually
     * consistent and cacheable by the adapter only once {@code ENABLED} (immutable, ADR-023). Empty
     * when it does not exist.
     */
    Mono<Event> findEvent(String eventId);

    /** AP-023: provisioning status with progress, strongly consistent. Empty when it does not exist. */
    Mono<ProvisioningSnapshot> findProvisioningSnapshot(String eventId);

    /**
     * AP-004: Events in {@code ENABLED} whose start instant is after {@code now}, ascending. An invalid
     * cursor is signalled as {@link com.nequi.ticketing.domain.error.ValidationException}.
     */
    Mono<EnabledEventPage> listEnabledUpcoming(Instant now, int limit, String cursor);

    /**
     * AP-024 "take lease": conditional on {@code provisioningStatus = PROVISIONING} and (no lease, or
     * {@code provisioningLeaseUntilMs < now}, or the lease already owned by {@code owner}); emits
     * {@code false} when the condition fails.
     */
    Mono<Boolean> acquireProvisioningLease(String eventId, String owner, Instant leaseUntil, Instant now);

    /**
     * AP-024 "check and record progress before a batch": renews the lease, records
     * {@code provisionedBatches} and {@code lastProgressAtMs = now}. Conditional on
     * {@code provisioningStatus = PROVISIONING} and the lease owned by {@code owner}; emits {@code false}
     * when the condition fails, in which case nothing may be written.
     */
    Mono<Boolean> recordProvisioningProgress(String eventId, String owner, Instant leaseUntil,
            int provisionedBatches, Instant now);

    /** AP-003: enable the Event with its audit (2 items). */
    Mono<TransactionOutcome> enable(EnablementPlan plan);

    /** AP-026: mark the Event {@code FAILED} with its audit (2 items). */
    Mono<TransactionOutcome> markFailed(ProvisioningFailurePlan plan);

    /**
     * AP-018: Events in {@code PROVISIONING} whose last progress is before {@code progressBefore},
     * eventually consistent.
     */
    Flux<StalledProvisioning> findStalledProvisioning(Instant progressBefore);

    /**
     * AP-033: {@code provisioningRepublishCount + 1} and {@code lastProgressAtMs = now}. Conditional on
     * {@code provisioningStatus = PROVISIONING} and {@code lastProgressAtMs} equal to
     * {@code expectedLastProgressAt}; emits {@code false} when the condition fails.
     */
    Mono<Boolean> registerRepublication(String eventId, Instant expectedLastProgressAt, Instant now);

    /** AP-027 query: identifiers of the {@code FAILED} Events still indexed for purge ({@code GSI1}). */
    Flux<String> findFailedPendingPurge();

    /**
     * AP-027 final mark: {@code ticketsPurgedAt}, which removes the Event from the purge index.
     * Conditional on {@code provisioningStatus = FAILED}; emits {@code false} when the condition fails.
     */
    Mono<Boolean> markTicketsPurged(String eventId, Instant purgedAt);
}
