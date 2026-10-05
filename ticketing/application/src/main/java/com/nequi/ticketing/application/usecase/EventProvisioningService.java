package com.nequi.ticketing.application.usecase;

import com.nequi.ticketing.application.port.in.DispositionReason;
import com.nequi.ticketing.application.port.in.MessageDisposition;
import com.nequi.ticketing.application.port.in.ProvisionEventCommand;
import com.nequi.ticketing.application.port.in.ProvisionEventUseCase;
import com.nequi.ticketing.application.port.in.ProvisioningProgressListener;
import com.nequi.ticketing.application.port.out.Clock;
import com.nequi.ticketing.application.port.out.EnablementPlan;
import com.nequi.ticketing.application.port.out.EventCatalog;
import com.nequi.ticketing.application.port.out.InventoryVerification;
import com.nequi.ticketing.application.port.out.ProvisioningFailurePlan;
import com.nequi.ticketing.application.port.out.ProvisioningSnapshot;
import com.nequi.ticketing.application.port.out.TicketInventory;
import com.nequi.ticketing.application.port.out.TransactionOutcome;
import com.nequi.ticketing.domain.audit.AuditRecord.Actor;
import com.nequi.ticketing.domain.audit.AuditRecord.ActorType;
import com.nequi.ticketing.domain.event.Event;
import com.nequi.ticketing.domain.event.InventoryDefinition.TicketSeed;
import com.nequi.ticketing.domain.event.InventoryDefinition.ValidatedInventory;
import com.nequi.ticketing.domain.messaging.ProvisioningMessagePolicy;
import com.nequi.ticketing.domain.ticket.Ticket;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * CMP-022 Event provisioning (FR-001, FR-003, BR-016, BR-021, BR-026, VAL-009, ST-012, ST-013; ADR-024):
 * applies rules 1..10 of {@code ticketing.messaging.v2.md} §5.2 through {@link ProvisioningMessagePolicy}.
 * It takes the conditional lease (AP-024), resumes from {@code provisionedBatches}, checks the Event and
 * the lease before every batch (AP-024) and writes the batch only after a successful check (AP-002),
 * verifies every generated key with strong consistency (AP-025) rewriting missing Tickets up to three
 * times, and enables the Event (AP-003) only when exactly {@code capacity} Tickets exist in their
 * initial state. A redelivery for an Event already {@code ENABLED} or {@code FAILED} changes nothing. A
 * transient failure on the last reception marks the Event {@code FAILED} (AP-026).
 */
public final class EventProvisioningService implements ProvisionEventUseCase {

    private final EventCatalog eventCatalog;
    private final TicketInventory ticketInventory;
    private final Clock clock;
    private final WorkerUseCaseSettings settings;
    private final Actor actor;
    private final AtomicLong runs = new AtomicLong();

    public EventProvisioningService(
            EventCatalog eventCatalog,
            TicketInventory ticketInventory,
            Clock clock,
            WorkerUseCaseSettings settings) {
        this.eventCatalog = Objects.requireNonNull(eventCatalog, "eventCatalog");
        this.ticketInventory = Objects.requireNonNull(ticketInventory, "ticketInventory");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.actor = new Actor(ActorType.WORKER, settings.workerId());
    }

    @Override
    public Mono<MessageDisposition> provision(ProvisionEventCommand command) {
        return Mono.defer(() -> {
            Objects.requireNonNull(command, "command");
            if (!command.readable()) {
                return Mono.just(MessageDisposition.poison(DispositionReason.UNREADABLE_MESSAGE));
            }
            Run run = new Run(command.eventId(), command.correlationId(), command.delivery().lastReception(),
                    settings.workerId() + "/" + runs.incrementAndGet(), command.progress());
            return evaluate(run, 0).onErrorResume(error -> transientFailure(run));
        });
    }

    // ------------------------------------------------------------------ rules 2..4 and lease (rule 5)

    private Mono<MessageDisposition> evaluate(Run run, int evaluations) {
        if (evaluations > settings.maximumReevaluations()) {
            return Mono.error(new TransientFailure("too many re-evaluations"));
        }
        return eventCatalog.findProvisioningSnapshot(run.eventId())
                .flatMap(snapshot -> decide(run, snapshot, evaluations))
                .switchIfEmpty(Mono.fromSupplier(() -> MessageDisposition.poison(DispositionReason.ENTITY_NOT_FOUND)));
    }

    private Mono<MessageDisposition> decide(Run run, ProvisioningSnapshot snapshot, int evaluations) {
        Instant now = clock.now();
        ProvisioningMessagePolicy.Action action = ProvisioningMessagePolicy.decide(new ProvisioningMessagePolicy.Snapshot(
                true, true, snapshot.event().provisioningStatus(), snapshot.leaseHeldByOtherAt(run.leaseOwner(), now),
                false, false, false, false, 0, false, run.lastReception()), settings.maximumVerificationRepairs());
        return switch (action) {
            case DELETE_NOOP -> Mono.just(MessageDisposition.delete(DispositionReason.ALREADY_TERMINAL));
            case POSTPONE_TO_LEASE_END -> Mono.just(MessageDisposition.postponeUntil(
                    snapshot.leaseUntil(), DispositionReason.LEASE_HELD_ELSEWHERE));
            case ACQUIRE_LEASE -> eventCatalog.acquireProvisioningLease(
                            run.eventId(), run.leaseOwner(), now.plus(settings.provisioningLeaseDuration()), now)
                    .defaultIfEmpty(Boolean.FALSE)
                    .flatMap(acquired -> acquired
                            ? writeBatches(run, snapshot, evaluations)
                            : evaluate(run, evaluations + 1));
            default -> Mono.error(new IllegalStateException("unexpected provisioning action " + action));
        };
    }

    // ------------------------------------------------------------------ rules 5 and 6 (batches)

    private Mono<MessageDisposition> writeBatches(Run run, ProvisioningSnapshot snapshot, int evaluations) {
        Event event = snapshot.event();
        ValidatedInventory inventory = event.inventoryDefinition().validate(event.capacity(), settings.inventoryLimits());
        List<List<TicketSeed>> batches = inventory.batches();
        int first = Math.min(snapshot.provisionedBatches(), batches.size());
        return Flux.range(first, batches.size() - first)
                .concatMap(index -> checkpoint(run, index).flatMap(checked -> checked
                        ? write(event, batches.get(index))
                                .then(Mono.fromRunnable(() -> run.progress().batchWritten(index + 1)))
                                .thenReturn(Boolean.TRUE)
                        : Mono.just(Boolean.FALSE)))
                .takeUntil(written -> !written)
                .all(written -> written)
                .flatMap(allWritten -> allWritten
                        ? verify(run, event, inventory, 0, evaluations)
                        // Rule 6: the check before a batch failed; nothing was written, re-read and re-evaluate.
                        : evaluate(run, evaluations + 1));
    }

    /** AP-024 check and progress before a batch; {@code false} means: stop without writing. */
    private Mono<Boolean> checkpoint(Run run, int provisionedBatches) {
        Instant now = clock.now();
        return eventCatalog.recordProvisioningProgress(run.eventId(), run.leaseOwner(),
                        now.plus(settings.provisioningLeaseDuration()), provisionedBatches, now)
                .defaultIfEmpty(Boolean.FALSE);
    }

    private Mono<Void> write(Event event, List<TicketSeed> seeds) {
        List<Ticket> tickets = seeds.stream().map(seed -> Ticket.provision(event.eventId(), seed)).toList();
        return ticketInventory.writeBatch(event, tickets);
    }

    // ------------------------------------------------------------------ rules 7 and 8 (verification, enable)

    private Mono<MessageDisposition> verify(Run run, Event event, ValidatedInventory inventory, int repairs,
            int evaluations) {
        List<Ticket> expected = inventory.tickets().stream().map(seed -> Ticket.provision(event.eventId(), seed)).toList();
        return ticketInventory.verify(event, expected).flatMap(verification -> {
            ProvisioningMessagePolicy.Action action = ProvisioningMessagePolicy.decide(new ProvisioningMessagePolicy.Snapshot(
                    true, true, event.provisioningStatus(), false, true, false, true, verification.complete(),
                    repairs, false, run.lastReception()), settings.maximumVerificationRepairs());
            return switch (action) {
                case ENABLE_AND_DELETE -> enable(run, event, inventory, verification, evaluations);
                case REPAIR_AND_VERIFY -> repair(run, event, inventory, verification).flatMap(repaired -> repaired
                        ? verify(run, event, inventory, repairs + 1, evaluations)
                        : evaluate(run, evaluations + 1));
                // ADR-024: a verification that keeps failing is a transient failure of the message.
                default -> transientFailure(run);
            };
        });
    }

    /** Rewrites the missing Tickets in batches, each one after its own AP-024 check. */
    private Mono<Boolean> repair(Run run, Event event, ValidatedInventory inventory, InventoryVerification verification) {
        Set<String> invalid = new HashSet<>(verification.invalidTicketIds());
        List<TicketSeed> missing = inventory.tickets().stream().filter(seed -> invalid.contains(seed.ticketId())).toList();
        List<List<TicketSeed>> chunks = new ArrayList<>();
        int size = settings.inventoryLimits().provisioningBatchSize();
        for (int start = 0; start < missing.size(); start += size) {
            chunks.add(missing.subList(start, Math.min(start + size, missing.size())));
        }
        int totalBatches = inventory.batches().size();
        return Flux.fromIterable(chunks)
                .concatMap(chunk -> checkpoint(run, totalBatches).flatMap(checked -> checked
                        ? write(event, chunk).thenReturn(Boolean.TRUE)
                        : Mono.just(Boolean.FALSE)))
                .takeUntil(written -> !written)
                .all(written -> written);
    }

    private Mono<MessageDisposition> enable(Run run, Event event, ValidatedInventory inventory,
            InventoryVerification verification, int evaluations) {
        Instant now = clock.now();
        Event enabled = event.enable(verification.verifiedCount());
        EnablementPlan plan = new EnablementPlan(event, enabled, run.leaseOwner(),
                WorkerAudits.eventEnabled(enabled, (int) inventory.complimentaryCount(), actor, run.correlationId(), now),
                now);
        return eventCatalog.enable(plan).flatMap(outcome -> switch (outcome) {
            case TransactionOutcome.Applied applied -> Mono.just(MessageDisposition.delete(DispositionReason.ENABLED));
            case TransactionOutcome.Cancelled cancelled -> evaluate(run, evaluations + 1);
            case TransactionOutcome.Conflict conflict -> Mono.error(new TransientFailure("enable conflict"));
        });
    }

    // ------------------------------------------------------------------ rules 9 and 10 (transient failures)

    private Mono<MessageDisposition> transientFailure(Run run) {
        if (!run.lastReception()) {
            return Mono.just(MessageDisposition.retry(DispositionReason.TRANSIENT_FAILURE));
        }
        return eventCatalog.findProvisioningSnapshot(run.eventId())
                .flatMap(snapshot -> {
                    Instant now = clock.now();
                    ProvisioningMessagePolicy.Action action = ProvisioningMessagePolicy.decide(
                            new ProvisioningMessagePolicy.Snapshot(true, true, snapshot.event().provisioningStatus(),
                                    snapshot.leaseHeldByOtherAt(run.leaseOwner(), now), false, false, false, false, 0,
                                    true, true), settings.maximumVerificationRepairs());
                    return switch (action) {
                        case DELETE_NOOP -> Mono.just(MessageDisposition.delete(DispositionReason.ALREADY_TERMINAL));
                        case POSTPONE_TO_LEASE_END -> Mono.just(MessageDisposition.postponeUntil(
                                snapshot.leaseUntil(), DispositionReason.LEASE_HELD_ELSEWHERE));
                        default -> markFailed(run, snapshot.event(), now);
                    };
                })
                .switchIfEmpty(Mono.fromSupplier(() -> MessageDisposition.poison(DispositionReason.ENTITY_NOT_FOUND)))
                .onErrorResume(error -> Mono.just(MessageDisposition.retry(DispositionReason.TRANSIENT_FAILURE)));
    }

    /** Rule 10: AP-026; the message is not deleted and moves to the DLQ. */
    private Mono<MessageDisposition> markFailed(Run run, Event event, Instant now) {
        Event failed = event.fail();
        ProvisioningFailurePlan plan = new ProvisioningFailurePlan(event, failed,
                WorkerAudits.eventProvisioningFailed(failed, actor, run.correlationId(), now), now);
        return eventCatalog.markFailed(plan).map(outcome -> outcome instanceof TransactionOutcome.Applied
                ? MessageDisposition.retry(DispositionReason.EXHAUSTED)
                : MessageDisposition.retry(DispositionReason.TRANSIENT_FAILURE));
    }

    /** One processing of one delivery; {@code leaseOwner} is unique per processing and worker instance. */
    private record Run(String eventId, String correlationId, boolean lastReception, String leaseOwner,
            ProvisioningProgressListener progress) {
    }

    private static final class TransientFailure extends RuntimeException {
        TransientFailure(String message) {
            super(message, null, false, false);
        }
    }
}
