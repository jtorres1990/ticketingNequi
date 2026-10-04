package com.nequi.ticketing.application.port.out;

import com.nequi.ticketing.domain.event.Event;
import java.time.Instant;
import reactor.core.publisher.Mono;

/**
 * Outbound port: Event catalog operations used by the {@code api} role (ADR-034). Worker operations
 * (lease, progress, enable, fail, stalled detection, purge) are added with their use cases.
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
}
