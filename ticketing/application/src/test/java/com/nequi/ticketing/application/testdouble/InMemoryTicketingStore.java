package com.nequi.ticketing.application.testdouble;

import com.nequi.ticketing.application.port.out.AvailableTicket;
import com.nequi.ticketing.application.port.out.AvailableTicketPage;
import com.nequi.ticketing.application.port.out.ClosurePlan;
import com.nequi.ticketing.application.port.out.ConfirmationPlan;
import com.nequi.ticketing.application.port.out.EnablementPlan;
import com.nequi.ticketing.application.port.out.EnabledEventPage;
import com.nequi.ticketing.application.port.out.EnqueueFailurePlan;
import com.nequi.ticketing.application.port.out.EventCatalog;
import com.nequi.ticketing.application.port.out.FailedItem;
import com.nequi.ticketing.application.port.out.IdempotencyRecord;
import com.nequi.ticketing.application.port.out.IdempotencyStore;
import com.nequi.ticketing.application.port.out.InventoryVerification;
import com.nequi.ticketing.application.port.out.ItemFailure;
import com.nequi.ticketing.application.port.out.LateApprovalPlan;
import com.nequi.ticketing.application.port.out.NewEventPlan;
import com.nequi.ticketing.application.port.out.OrderLifecycleStore;
import com.nequi.ticketing.application.port.out.OrderReader;
import com.nequi.ticketing.application.port.out.OrderRecord;
import com.nequi.ticketing.application.port.out.PaymentLease;
import com.nequi.ticketing.application.port.out.PaymentStartPlan;
import com.nequi.ticketing.application.port.out.ProvisioningFailurePlan;
import com.nequi.ticketing.application.port.out.ProvisioningSnapshot;
import com.nequi.ticketing.application.port.out.QuarantinePlan;
import com.nequi.ticketing.application.port.out.ReservationPlan;
import com.nequi.ticketing.application.port.out.ReversalCompletionPlan;
import com.nequi.ticketing.application.port.out.ReversalExhaustionPlan;
import com.nequi.ticketing.application.port.out.StalledProvisioning;
import com.nequi.ticketing.application.port.out.TicketInventory;
import com.nequi.ticketing.application.port.out.TransactionOutcome;
import com.nequi.ticketing.domain.audit.AuditRecord;
import com.nequi.ticketing.domain.error.ValidationException;
import com.nequi.ticketing.domain.event.Event;
import com.nequi.ticketing.domain.event.Event.ProvisioningStatus;
import com.nequi.ticketing.domain.event.InventoryDefinition;
import com.nequi.ticketing.domain.event.InventoryDefinition.TicketSeed;
import com.nequi.ticketing.domain.event.InventoryLimits;
import com.nequi.ticketing.domain.event.ShardingPolicy;
import com.nequi.ticketing.domain.order.ActiveOrderKey;
import com.nequi.ticketing.domain.order.Order;
import com.nequi.ticketing.domain.order.Order.OrderStatus;
import com.nequi.ticketing.domain.order.ReversalPlan;
import com.nequi.ticketing.domain.ticket.Ticket;
import com.nequi.ticketing.domain.ticket.Ticket.TicketState;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/**
 * In-memory double of the persistence ports (ADR-038). Every write evaluates exactly the items and
 * conditions of {@code ticketing.data-model.v2.md} §5 atomically under one monitor and reports the
 * reason per failed item, so it reproduces the single winner per Ticket, the active Order lock, the
 * quarantine (lock retained, no Ticket written), the PaymentAttempt lease, the payment reversal mark
 * carried by the Order and the provisioning lease of the Event. The index queries (AP-016, AP-018,
 * AP-027, AP-028, AP-029) follow the sparse index membership rules of §2.2. Operations can be held at a
 * non-blocking gate, fail with a simulated transient error, return scripted outcomes or run a hook
 * just before they are evaluated, to reproduce races deterministically.
 */
public final class InMemoryTicketingStore
        implements EventCatalog, TicketInventory, OrderLifecycleStore, OrderReader, IdempotencyStore {

    /** Operations that support fault injection, gates, scripted outcomes and hooks. */
    public enum Operation {
        FIND_ORDER, FIND_DUE_RESERVATIONS, FIND_PENDING_ENQUEUE, FIND_DUE_REVERSALS,
        START_PAYMENT, CLAIM_LEASE, CONFIRM, CLOSE, LATE_APPROVAL, QUARANTINE,
        COMPLETE_REVERSAL, EXHAUST_REVERSAL, RESCHEDULE_REVERSAL,
        FIND_SNAPSHOT, ACQUIRE_PROVISIONING_LEASE, RECORD_PROGRESS, WRITE_BATCH, VERIFY, ENABLE, MARK_FAILED,
        FIND_STALLED, REGISTER_REPUBLICATION, FIND_FAILED, PURGE, MARK_PURGED
    }

    private final Object monitor = new Object();
    private final Map<String, ProvisioningSnapshot> events = new HashMap<>();
    private final Map<String, Map<String, Ticket>> tickets = new HashMap<>();
    private final Map<String, OrderRecord> orders = new LinkedHashMap<>();
    private final Map<String, IdempotencyRecord> purchaseIdempotency = new HashMap<>();
    private final Map<String, IdempotencyRecord> eventIdempotency = new HashMap<>();
    private final Map<ActiveOrderKey, String> activeLocks = new HashMap<>();
    private final List<AuditRecord> audits = new ArrayList<>();

    private final Deque<TransactionOutcome> scriptedReservations = new ArrayDeque<>();
    private final Deque<TransactionOutcome> scriptedEnqueueFailures = new ArrayDeque<>();
    private final AtomicInteger waitingReservations = new AtomicInteger();
    private final AtomicInteger reservationAttempts = new AtomicInteger();
    private final AtomicInteger countQueries = new AtomicInteger();
    private volatile Sinks.Empty<Void> reservationGate;
    private volatile boolean markEnqueuedFails;

    private final Map<Operation, Integer> failures = new EnumMap<>(Operation.class);
    private final Map<Operation, Deque<TransactionOutcome>> scripted = new EnumMap<>(Operation.class);
    private final Map<Operation, Sinks.Empty<Void>> gates = new ConcurrentHashMap<>();
    private final Map<Operation, AtomicInteger> waiting = new ConcurrentHashMap<>();
    private final Map<Operation, AtomicInteger> calls = new ConcurrentHashMap<>();
    private final Map<Operation, Runnable> hooks = new ConcurrentHashMap<>();
    private final List<List<String>> writtenBatches = new ArrayList<>();

    // ----------------------------------------------------------------- EventCatalog

    @Override
    public Mono<TransactionOutcome> create(NewEventPlan plan) {
        return Mono.fromCallable(() -> {
            synchronized (monitor) {
                List<ItemFailure> failed = new ArrayList<>();
                if (events.containsKey(plan.event().eventId())) {
                    failed.add(ItemFailure.of(FailedItem.EVENT));
                }
                if (eventIdempotency.containsKey(key(plan.idempotency()))) {
                    failed.add(ItemFailure.of(FailedItem.IDEMPOTENCY_RECORD));
                }
                if (audits.contains(plan.audit())) {
                    failed.add(ItemFailure.of(FailedItem.AUDIT));
                }
                if (!failed.isEmpty()) {
                    return TransactionOutcome.cancelled(failed);
                }
                events.put(plan.event().eventId(), new ProvisioningSnapshot(plan.event(), plan.createdAt(), 0, null, null,
                        null, null, plan.createdAt(), 0, null));
                eventIdempotency.put(key(plan.idempotency()), plan.idempotency());
                audits.add(plan.audit());
                return TransactionOutcome.applied();
            }
        });
    }

    @Override
    public Mono<Event> findEvent(String eventId) {
        return Mono.fromCallable(() -> {
            synchronized (monitor) {
                return Optional.ofNullable(events.get(eventId)).map(ProvisioningSnapshot::event);
            }
        }).flatMap(Mono::justOrEmpty);
    }

    @Override
    public Mono<ProvisioningSnapshot> findProvisioningSnapshot(String eventId) {
        return guarded(Operation.FIND_SNAPSHOT, () -> {
            synchronized (monitor) {
                return Optional.ofNullable(events.get(eventId));
            }
        }).flatMap(Mono::justOrEmpty);
    }

    @Override
    public Mono<EnabledEventPage> listEnabledUpcoming(Instant now, int limit, String cursor) {
        return Mono.fromCallable(() -> {
            int offset = eventCursorOffset(cursor);
            synchronized (monitor) {
                List<Event> enabled = events.values().stream()
                        .map(ProvisioningSnapshot::event)
                        .filter(event -> event.provisioningStatus() == ProvisioningStatus.ENABLED)
                        .filter(event -> event.startsAt().isAfter(now))
                        .sorted(Comparator.comparing(Event::startsAt).thenComparing(Event::eventId))
                        .toList();
                List<Event> page = enabled.stream().skip(offset).limit(limit).toList();
                String next = offset + page.size() < enabled.size() ? "events:" + (offset + page.size()) : null;
                return new EnabledEventPage(page, next);
            }
        });
    }

    @Override
    public Mono<Boolean> acquireProvisioningLease(String eventId, String owner, Instant leaseUntil, Instant now) {
        return guarded(Operation.ACQUIRE_PROVISIONING_LEASE, () -> {
            synchronized (monitor) {
                ProvisioningSnapshot current = events.get(eventId);
                if (current == null || current.event().provisioningStatus() != ProvisioningStatus.PROVISIONING) {
                    return false;
                }
                boolean free = current.leaseOwner() == null || current.leaseUntil().isBefore(now)
                        || current.leaseOwner().equals(owner);
                if (!free) {
                    return false;
                }
                events.put(eventId, snapshot(current, current.event(), current.provisionedBatches(), owner, leaseUntil,
                        current.lastProgressAt(), current.republishCount(), current.enabledAt(), current.failedAt(),
                        current.ticketsPurgedAt()));
                return true;
            }
        });
    }

    @Override
    public Mono<Boolean> recordProvisioningProgress(String eventId, String owner, Instant leaseUntil,
            int provisionedBatches, Instant now) {
        return guarded(Operation.RECORD_PROGRESS, () -> {
            synchronized (monitor) {
                ProvisioningSnapshot current = events.get(eventId);
                if (current == null || current.event().provisioningStatus() != ProvisioningStatus.PROVISIONING
                        || !owner.equals(current.leaseOwner())) {
                    return false;
                }
                events.put(eventId, snapshot(current, current.event(), provisionedBatches, owner, leaseUntil, now,
                        current.republishCount(), current.enabledAt(), current.failedAt(), current.ticketsPurgedAt()));
                return true;
            }
        });
    }

    @Override
    public Mono<TransactionOutcome> enable(EnablementPlan plan) {
        return guarded(Operation.ENABLE, () -> {
            synchronized (monitor) {
                TransactionOutcome outcome = scriptedOutcome(Operation.ENABLE);
                if (outcome != null) {
                    return outcome;
                }
                ProvisioningSnapshot current = events.get(plan.current().eventId());
                List<ItemFailure> failed = new ArrayList<>();
                if (current == null || current.event().provisioningStatus() != ProvisioningStatus.PROVISIONING
                        || !plan.leaseOwner().equals(current.leaseOwner())) {
                    failed.add(ItemFailure.of(FailedItem.EVENT));
                }
                if (audits.contains(plan.audit())) {
                    failed.add(ItemFailure.of(FailedItem.AUDIT));
                }
                if (!failed.isEmpty()) {
                    return TransactionOutcome.cancelled(failed);
                }
                events.put(plan.enabled().eventId(), snapshot(current, plan.enabled(), current.provisionedBatches(), null,
                        null, current.lastProgressAt(), current.republishCount(), plan.enabledAt(), null, null));
                audits.add(plan.audit());
                return TransactionOutcome.applied();
            }
        });
    }

    @Override
    public Mono<TransactionOutcome> markFailed(ProvisioningFailurePlan plan) {
        return guarded(Operation.MARK_FAILED, () -> {
            synchronized (monitor) {
                TransactionOutcome outcome = scriptedOutcome(Operation.MARK_FAILED);
                if (outcome != null) {
                    return outcome;
                }
                ProvisioningSnapshot current = events.get(plan.current().eventId());
                List<ItemFailure> failed = new ArrayList<>();
                if (current == null || current.event().provisioningStatus() != ProvisioningStatus.PROVISIONING) {
                    failed.add(ItemFailure.of(FailedItem.EVENT));
                }
                if (audits.contains(plan.audit())) {
                    failed.add(ItemFailure.of(FailedItem.AUDIT));
                }
                if (!failed.isEmpty()) {
                    return TransactionOutcome.cancelled(failed);
                }
                events.put(plan.failed().eventId(), snapshot(current, plan.failed(), current.provisionedBatches(), null,
                        null, current.lastProgressAt(), current.republishCount(), null, plan.failedAt(), null));
                audits.add(plan.audit());
                return TransactionOutcome.applied();
            }
        });
    }

    @Override
    public Flux<StalledProvisioning> findStalledProvisioning(Instant progressBefore) {
        return guarded(Operation.FIND_STALLED, () -> {
            synchronized (monitor) {
                return events.values().stream()
                        .filter(snapshot -> snapshot.event().provisioningStatus() == ProvisioningStatus.PROVISIONING)
                        .filter(snapshot -> snapshot.progressReference().isBefore(progressBefore))
                        .sorted(Comparator.comparing(ProvisioningSnapshot::createdAt))
                        .map(snapshot -> new StalledProvisioning(snapshot.event().eventId(),
                                snapshot.progressReference(), snapshot.republishCount()))
                        .toList();
            }
        }).flatMapMany(Flux::fromIterable);
    }

    @Override
    public Mono<Boolean> registerRepublication(String eventId, Instant expectedLastProgressAt, Instant now) {
        return guarded(Operation.REGISTER_REPUBLICATION, () -> {
            synchronized (monitor) {
                ProvisioningSnapshot current = events.get(eventId);
                if (current == null || current.event().provisioningStatus() != ProvisioningStatus.PROVISIONING
                        || !current.progressReference().equals(expectedLastProgressAt)) {
                    return false;
                }
                events.put(eventId, snapshot(current, current.event(), current.provisionedBatches(), current.leaseOwner(),
                        current.leaseUntil(), now, current.republishCount() + 1, current.enabledAt(),
                        current.failedAt(), current.ticketsPurgedAt()));
                return true;
            }
        });
    }

    @Override
    public Flux<String> findFailedPendingPurge() {
        return guarded(Operation.FIND_FAILED, () -> {
            synchronized (monitor) {
                return events.values().stream()
                        .filter(snapshot -> snapshot.event().provisioningStatus() == ProvisioningStatus.FAILED)
                        .filter(snapshot -> snapshot.ticketsPurgedAt() == null)
                        .map(snapshot -> snapshot.event().eventId())
                        .sorted()
                        .toList();
            }
        }).flatMapMany(Flux::fromIterable);
    }

    @Override
    public Mono<Boolean> markTicketsPurged(String eventId, Instant purgedAt) {
        return guarded(Operation.MARK_PURGED, () -> {
            synchronized (monitor) {
                ProvisioningSnapshot current = events.get(eventId);
                if (current == null || current.event().provisioningStatus() != ProvisioningStatus.FAILED) {
                    return false;
                }
                events.put(eventId, snapshot(current, current.event(), current.provisionedBatches(), null, null,
                        current.lastProgressAt(), current.republishCount(), current.enabledAt(), current.failedAt(),
                        purgedAt));
                return true;
            }
        });
    }

    // ----------------------------------------------------------------- TicketInventory

    @Override
    public Mono<Boolean> hasAvailable(Event event) {
        return Mono.fromCallable(() -> {
            synchronized (monitor) {
                return ticketsOf(event.eventId()).values().stream().anyMatch(ticket -> ticket.state() == TicketState.AVAILABLE);
            }
        });
    }

    @Override
    public Mono<Long> countAvailable(Event event) {
        return Mono.fromCallable(() -> {
            countQueries.incrementAndGet();
            synchronized (monitor) {
                return ticketsOf(event.eventId()).values().stream()
                        .filter(ticket -> ticket.state() == TicketState.AVAILABLE)
                        .count();
            }
        });
    }

    @Override
    public Mono<AvailableTicketPage> findAvailablePage(Event event, String section, int pageSize, String cursor) {
        return Mono.fromCallable(() -> {
            String after = availabilityCursorPosition(event, section, cursor);
            synchronized (monitor) {
                List<Ticket> available = ticketsOf(event.eventId()).values().stream()
                        .filter(ticket -> ticket.state() == TicketState.AVAILABLE)
                        .filter(ticket -> section == null || ticket.section().equals(section))
                        .sorted(Comparator.comparing(Ticket::section).thenComparing(Ticket::row).thenComparingInt(Ticket::seat))
                        .toList();
                int start = 0;
                if (after != null) {
                    while (start < available.size() && !available.get(start).ticketId().equals(after)) {
                        start++;
                    }
                    start++;
                }
                List<Ticket> page = available.stream().skip(start).limit(pageSize).toList();
                String next = start + page.size() < available.size()
                        ? event.eventId() + "|" + (section == null ? "" : section) + "|" + page.getLast().ticketId()
                        : null;
                return new AvailableTicketPage(page.stream()
                        .map(ticket -> new AvailableTicket(ticket.ticketId(), ticket.section(), ticket.row(), ticket.seat()))
                        .toList(), next);
            }
        });
    }

    @Override
    public Mono<Void> writeBatch(Event event, List<Ticket> batch) {
        return guarded(Operation.WRITE_BATCH, () -> {
            synchronized (monitor) {
                Map<String, Ticket> eventTickets = ticketsOf(event.eventId());
                for (Ticket ticket : batch) {
                    eventTickets.put(ticket.ticketId(), ticket);
                }
                writtenBatches.add(batch.stream().map(Ticket::ticketId).toList());
                return Boolean.TRUE;
            }
        }).then();
    }

    @Override
    public Mono<InventoryVerification> verify(Event event, List<Ticket> expected) {
        return guarded(Operation.VERIFY, () -> {
            synchronized (monitor) {
                Map<String, Ticket> eventTickets = ticketsOf(event.eventId());
                List<String> invalid = new ArrayList<>();
                int verified = 0;
                for (Ticket wanted : expected) {
                    Ticket stored = eventTickets.get(wanted.ticketId());
                    if (stored != null && stored.state() == wanted.state() && stored.orderId() == null) {
                        verified++;
                    } else {
                        invalid.add(wanted.ticketId());
                    }
                }
                return new InventoryVerification(verified, invalid);
            }
        });
    }

    @Override
    public Mono<Void> purge(Event event, List<String> ticketIds) {
        return guarded(Operation.PURGE, () -> {
            synchronized (monitor) {
                Map<String, Ticket> eventTickets = ticketsOf(event.eventId());
                ticketIds.forEach(eventTickets::remove);
                return Boolean.TRUE;
            }
        }).then();
    }

    // ----------------------------------------------------------------- OrderLifecycleStore (api role)

    @Override
    public Mono<TransactionOutcome> reserve(ReservationPlan plan) {
        waitingReservations.incrementAndGet();
        Sinks.Empty<Void> gate = reservationGate;
        Mono<Void> wait = gate == null ? Mono.empty() : gate.asMono().publishOn(Schedulers.parallel());
        return wait.then(Mono.fromCallable(() -> {
            reservationAttempts.incrementAndGet();
            synchronized (monitor) {
                TransactionOutcome outcome = scriptedReservations.poll();
                return outcome != null ? outcome : applyReservation(plan);
            }
        }));
    }

    @Override
    public Mono<Boolean> markEnqueued(String orderId, Instant enqueuedAt) {
        return Mono.fromCallable(() -> {
            if (markEnqueuedFails) {
                throw new IllegalStateException("simulated store failure");
            }
            synchronized (monitor) {
                OrderRecord current = orders.get(orderId);
                if (current == null || current.order().status() != OrderStatus.CREATED || current.enqueued()) {
                    return false;
                }
                orders.put(orderId, new OrderRecord(current.order(), current.createdAt(), current.updatedAt(),
                        enqueuedAt, current.paymentLease()));
                return true;
            }
        });
    }

    @Override
    public Mono<TransactionOutcome> failEnqueue(EnqueueFailurePlan plan) {
        return Mono.fromCallable(() -> {
            synchronized (monitor) {
                TransactionOutcome outcome = scriptedEnqueueFailures.poll();
                return outcome != null ? outcome : applyEnqueueFailure(plan);
            }
        });
    }

    @Override
    public Mono<TransactionOutcome> quarantine(QuarantinePlan plan) {
        return guarded(Operation.QUARANTINE, () -> {
            synchronized (monitor) {
                TransactionOutcome outcome = scriptedOutcome(Operation.QUARANTINE);
                if (outcome != null) {
                    return outcome;
                }
                OrderRecord current = orders.get(plan.current().orderId());
                if (current == null || current.order().status() != OrderStatus.CREATED
                        || current.order().quarantinedAt() != null) {
                    return TransactionOutcome.cancelled(List.of(ItemFailure.of(FailedItem.ORDER)));
                }
                orders.put(current.order().orderId(), new OrderRecord(plan.quarantined(), current.createdAt(),
                        plan.quarantined().quarantinedAt(), current.enqueuedAt(), current.paymentLease()));
                audits.add(plan.audit());
                return TransactionOutcome.applied();
            }
        });
    }

    // ----------------------------------------------------------------- OrderLifecycleStore (worker role)

    @Override
    public Mono<TransactionOutcome> startPayment(PaymentStartPlan plan) {
        return guarded(Operation.START_PAYMENT, () -> {
            synchronized (monitor) {
                TransactionOutcome outcome = scriptedOutcome(Operation.START_PAYMENT);
                if (outcome != null) {
                    return outcome;
                }
                String orderId = plan.current().orderId();
                OrderRecord current = orders.get(orderId);
                List<ItemFailure> failed = new ArrayList<>();
                if (current == null || current.order().status() != OrderStatus.CREATED
                        || current.order().paymentAttempt() != null || current.order().quarantinedAt() != null
                        || !current.order().reservation().expiresAt().isAfter(plan.cutoffInstant())) {
                    failed.add(ItemFailure.of(FailedItem.ORDER));
                }
                checkTickets(plan.current(), List.of(TicketState.RESERVED), failed);
                checkAudit(plan.audit(), failed);
                if (!failed.isEmpty()) {
                    return TransactionOutcome.cancelled(failed);
                }
                Map<String, Ticket> eventTickets = ticketsOf(plan.current().eventId());
                for (String ticketId : plan.current().ticketIds()) {
                    eventTickets.put(ticketId, eventTickets.get(ticketId).startPayment(orderId));
                }
                orders.put(orderId, new OrderRecord(plan.started(), current.createdAt(), plan.now(),
                        current.enqueuedAt(), plan.lease()));
                audits.add(plan.audit());
                return TransactionOutcome.applied();
            }
        });
    }

    @Override
    public Mono<Boolean> claimPaymentLease(String orderId, String paymentAttemptId, PaymentLease lease, Instant now) {
        return guarded(Operation.CLAIM_LEASE, () -> {
            synchronized (monitor) {
                OrderRecord current = orders.get(orderId);
                if (current == null || current.order().status() != OrderStatus.CREATED
                        || current.order().paymentAttempt() == null
                        || !current.order().paymentAttempt().paymentAttemptId().equals(paymentAttemptId)
                        || current.order().quarantinedAt() != null
                        || (current.paymentLease() != null && !current.paymentLease().until().isBefore(now))) {
                    return false;
                }
                orders.put(orderId, new OrderRecord(current.order(), current.createdAt(), now, current.enqueuedAt(), lease));
                return true;
            }
        });
    }

    @Override
    public Mono<TransactionOutcome> confirm(ConfirmationPlan plan) {
        return guarded(Operation.CONFIRM, () -> {
            synchronized (monitor) {
                TransactionOutcome outcome = scriptedOutcome(Operation.CONFIRM);
                if (outcome != null) {
                    return outcome;
                }
                String orderId = plan.current().orderId();
                OrderRecord current = orders.get(orderId);
                List<ItemFailure> failed = new ArrayList<>();
                if (current == null || current.order().status() != OrderStatus.CREATED
                        || current.order().quarantinedAt() != null
                        || !sameAttempt(current.order(), plan.current())
                        || !current.order().reservation().expiresAt().isAfter(plan.now())) {
                    failed.add(ItemFailure.of(FailedItem.ORDER));
                }
                checkTickets(plan.current(), List.of(TicketState.PENDING_CONFIRMATION), failed);
                checkLock(plan.current(), failed);
                checkAudit(plan.audit(), failed);
                if (!failed.isEmpty()) {
                    return TransactionOutcome.cancelled(failed);
                }
                Map<String, Ticket> eventTickets = ticketsOf(plan.current().eventId());
                for (String ticketId : plan.current().ticketIds()) {
                    eventTickets.put(ticketId, eventTickets.get(ticketId).sell(orderId));
                }
                orders.put(orderId, new OrderRecord(plan.confirmed(), current.createdAt(), plan.now(),
                        current.enqueuedAt(), null));
                activeLocks.remove(ActiveOrderKey.of(plan.current()));
                audits.add(plan.audit());
                return TransactionOutcome.applied();
            }
        });
    }

    @Override
    public Mono<TransactionOutcome> close(ClosurePlan plan) {
        return guarded(Operation.CLOSE, () -> {
            synchronized (monitor) {
                TransactionOutcome outcome = scriptedOutcome(Operation.CLOSE);
                if (outcome != null) {
                    return outcome;
                }
                String orderId = plan.current().orderId();
                OrderRecord current = orders.get(orderId);
                List<ItemFailure> failed = new ArrayList<>();
                if (current == null || !closable(current.order(), plan)) {
                    failed.add(ItemFailure.of(FailedItem.ORDER));
                }
                checkTickets(plan.current(), List.of(TicketState.RESERVED, TicketState.PENDING_CONFIRMATION), failed);
                checkLock(plan.current(), failed);
                checkAudit(plan.audit(), failed);
                if (!failed.isEmpty()) {
                    return TransactionOutcome.cancelled(failed);
                }
                Map<String, Ticket> eventTickets = ticketsOf(plan.current().eventId());
                for (String ticketId : plan.current().ticketIds()) {
                    eventTickets.put(ticketId, eventTickets.get(ticketId).release(orderId));
                }
                orders.put(orderId, new OrderRecord(plan.closed(), current.createdAt(), plan.now(),
                        current.enqueuedAt(), null));
                activeLocks.remove(ActiveOrderKey.of(plan.current()));
                audits.add(plan.audit());
                return TransactionOutcome.applied();
            }
        });
    }

    @Override
    public Mono<TransactionOutcome> recordLateApproval(LateApprovalPlan plan) {
        return guarded(Operation.LATE_APPROVAL, () -> {
            synchronized (monitor) {
                TransactionOutcome outcome = scriptedOutcome(Operation.LATE_APPROVAL);
                if (outcome != null) {
                    return outcome;
                }
                OrderRecord current = orders.get(plan.current().orderId());
                List<ItemFailure> failed = new ArrayList<>();
                OrderStatus status = current == null ? null : current.order().status();
                if (current == null || (status != OrderStatus.EXPIRED && status != OrderStatus.FAILED
                        && status != OrderStatus.REJECTED) || !sameAttempt(current.order(), plan.current())) {
                    failed.add(ItemFailure.of(FailedItem.ORDER));
                }
                checkAudit(plan.audit(), failed);
                if (!failed.isEmpty()) {
                    return TransactionOutcome.cancelled(failed);
                }
                Order stored = current.order();
                // Data model §5: the mark is written only when no reversal is pending nor completed.
                Order updated = stored.reversalPlan() == null ? plan.updated() : stored;
                orders.put(stored.orderId(), new OrderRecord(updated, current.createdAt(), plan.audit().occurredAt(),
                        current.enqueuedAt(), current.paymentLease()));
                audits.add(plan.audit());
                return TransactionOutcome.applied();
            }
        });
    }

    @Override
    public Mono<TransactionOutcome> completeReversal(ReversalCompletionPlan plan) {
        return guarded(Operation.COMPLETE_REVERSAL, () -> {
            synchronized (monitor) {
                TransactionOutcome outcome = scriptedOutcome(Operation.COMPLETE_REVERSAL);
                if (outcome != null) {
                    return outcome;
                }
                OrderRecord current = orders.get(plan.current().orderId());
                List<ItemFailure> failed = new ArrayList<>();
                if (current == null || !current.order().reversalPending()
                        || !current.order().reversalPlan().paymentAttemptId()
                                .equals(plan.current().reversalPlan().paymentAttemptId())) {
                    failed.add(ItemFailure.of(FailedItem.ORDER));
                }
                checkAudit(plan.audit(), failed);
                if (!failed.isEmpty()) {
                    return TransactionOutcome.cancelled(failed);
                }
                orders.put(plan.current().orderId(), new OrderRecord(plan.completed(), current.createdAt(),
                        plan.audit().occurredAt(), current.enqueuedAt(), current.paymentLease()));
                audits.add(plan.audit());
                return TransactionOutcome.applied();
            }
        });
    }

    @Override
    public Mono<TransactionOutcome> exhaustReversal(ReversalExhaustionPlan plan) {
        return guarded(Operation.EXHAUST_REVERSAL, () -> {
            synchronized (monitor) {
                TransactionOutcome outcome = scriptedOutcome(Operation.EXHAUST_REVERSAL);
                if (outcome != null) {
                    return outcome;
                }
                OrderRecord current = orders.get(plan.current().orderId());
                List<ItemFailure> failed = new ArrayList<>();
                if (current == null || !current.order().reversalPending()) {
                    failed.add(ItemFailure.of(FailedItem.ORDER));
                }
                checkAudit(plan.audit(), failed);
                if (!failed.isEmpty()) {
                    return TransactionOutcome.cancelled(failed);
                }
                orders.put(plan.current().orderId(), new OrderRecord(plan.exhausted(), current.createdAt(),
                        plan.audit().occurredAt(), current.enqueuedAt(), current.paymentLease()));
                audits.add(plan.audit());
                return TransactionOutcome.applied();
            }
        });
    }

    @Override
    public Mono<Boolean> rescheduleReversal(String orderId, int expectedAttempts, ReversalPlan next) {
        return guarded(Operation.RESCHEDULE_REVERSAL, () -> {
            synchronized (monitor) {
                OrderRecord current = orders.get(orderId);
                if (current == null || !current.order().reversalPending()
                        || current.order().reversalPlan().attempts() != expectedAttempts) {
                    return false;
                }
                Order stored = current.order();
                Order rescheduled = new Order(stored.orderId(), stored.customerId(), stored.eventId(), stored.ticketIds(),
                        stored.status(), stored.failureCause(), stored.reservation(), stored.paymentAttempt(),
                        stored.quarantinedAt(), stored.quarantineReason(), next);
                orders.put(orderId, new OrderRecord(rescheduled, current.createdAt(), current.updatedAt(),
                        current.enqueuedAt(), current.paymentLease()));
                return true;
            }
        });
    }

    // ----------------------------------------------------------------- OrderReader / IdempotencyStore

    @Override
    public Mono<OrderRecord> findById(String orderId) {
        return guarded(Operation.FIND_ORDER, () -> {
            synchronized (monitor) {
                return Optional.ofNullable(orders.get(orderId));
            }
        }).flatMap(Mono::justOrEmpty);
    }

    /** AP-016: {@code RESV#<shard>} holds Orders in {@code CREATED} without quarantine, by expiry. */
    @Override
    public Flux<String> findDueReservations(int shard, Instant now) {
        return query(Operation.FIND_DUE_RESERVATIONS, record -> record.order().status() == OrderStatus.CREATED
                        && record.order().quarantinedAt() == null
                        && ShardingPolicy.shard(record.order().orderId(), ShardingPolicy.RESERVATION_SHARDS) == shard
                        && !record.order().reservation().expiresAt().isAfter(now),
                Comparator.comparing(record -> record.order().reservation().expiresAt()));
    }

    /** AP-028: {@code PENDQ#<shard>} holds Orders in {@code CREATED} without {@code enqueuedAt} nor quarantine. */
    @Override
    public Flux<String> findPendingEnqueue(int shard, Instant createdBefore) {
        return query(Operation.FIND_PENDING_ENQUEUE, record -> record.order().status() == OrderStatus.CREATED
                        && !record.enqueued()
                        && record.order().quarantinedAt() == null
                        && ShardingPolicy.shard(record.order().orderId(), ShardingPolicy.PENDING_ENQUEUE_SHARDS) == shard
                        && record.createdAt().isBefore(createdBefore),
                Comparator.comparing(OrderRecord::createdAt));
    }

    /** AP-029: {@code REVERSAL#<shard>} holds pending, not exhausted reversals, by next attempt. */
    @Override
    public Flux<String> findDueReversals(int shard, Instant now) {
        return query(Operation.FIND_DUE_REVERSALS, record -> record.order().reversalPending()
                        && !record.order().reversalPlan().exhausted()
                        && ShardingPolicy.shard(record.order().orderId(), ShardingPolicy.REVERSAL_SHARDS) == shard
                        && !record.order().reversalPlan().nextAttemptAt().isAfter(now),
                Comparator.comparing(record -> record.order().reversalPlan().nextAttemptAt()));
    }

    @Override
    public Mono<IdempotencyRecord> findPurchase(String customerId, String idempotencyKey) {
        return Mono.fromCallable(() -> {
            synchronized (monitor) {
                return Optional.ofNullable(purchaseIdempotency.get(customerId + "#" + idempotencyKey));
            }
        }).flatMap(Mono::justOrEmpty);
    }

    @Override
    public Mono<IdempotencyRecord> findEventCreation(String adminSubject, String idempotencyKey) {
        return Mono.fromCallable(() -> {
            synchronized (monitor) {
                return Optional.ofNullable(eventIdempotency.get(adminSubject + "#" + idempotencyKey));
            }
        }).flatMap(Mono::justOrEmpty);
    }

    // ----------------------------------------------------------------- transactions (data model §5)

    private TransactionOutcome applyReservation(ReservationPlan plan) {
        Order order = plan.order();
        Map<String, Ticket> eventTickets = ticketsOf(order.eventId());
        List<ItemFailure> failed = new ArrayList<>();
        for (String ticketId : order.ticketIds()) {
            Ticket ticket = eventTickets.get(ticketId);
            if (ticket == null || !ticket.eventId().equals(order.eventId())) {
                failed.add(ItemFailure.ticket(FailedItem.TICKET_MISSING, ticketId));
            } else if (ticket.state() != TicketState.AVAILABLE) {
                failed.add(ItemFailure.ticket(FailedItem.TICKET_STATE, ticketId));
            }
        }
        if (orders.containsKey(order.orderId())) {
            failed.add(ItemFailure.of(FailedItem.ORDER));
        }
        if (purchaseIdempotency.containsKey(key(plan.idempotency()))) {
            failed.add(ItemFailure.of(FailedItem.IDEMPOTENCY_RECORD));
        }
        checkAudit(plan.audit(), failed);
        if (activeLocks.containsKey(plan.activeOrderKey())) {
            failed.add(ItemFailure.of(FailedItem.ACTIVE_ORDER_LOCK));
        }
        if (!failed.isEmpty()) {
            return TransactionOutcome.cancelled(failed);
        }
        for (String ticketId : order.ticketIds()) {
            eventTickets.put(ticketId, eventTickets.get(ticketId).reserve(order.orderId()));
        }
        Instant createdAt = order.reservation().reservedAt();
        orders.put(order.orderId(), new OrderRecord(order, createdAt, createdAt, null));
        purchaseIdempotency.put(key(plan.idempotency()), plan.idempotency());
        audits.add(plan.audit());
        activeLocks.put(plan.activeOrderKey(), order.orderId());
        return TransactionOutcome.applied();
    }

    private TransactionOutcome applyEnqueueFailure(EnqueueFailurePlan plan) {
        String orderId = plan.current().orderId();
        OrderRecord current = orders.get(orderId);
        List<ItemFailure> failed = new ArrayList<>();
        if (current == null || current.order().status() != OrderStatus.CREATED || current.order().paymentAttempt() != null) {
            failed.add(ItemFailure.of(FailedItem.ORDER));
        }
        checkTickets(plan.current(), List.of(TicketState.RESERVED, TicketState.PENDING_CONFIRMATION), failed);
        checkLock(plan.current(), failed);
        checkAudit(plan.audit(), failed);
        if (!failed.isEmpty()) {
            return TransactionOutcome.cancelled(failed);
        }
        Map<String, Ticket> eventTickets = ticketsOf(plan.current().eventId());
        for (String ticketId : plan.current().ticketIds()) {
            eventTickets.put(ticketId, eventTickets.get(ticketId).release(orderId));
        }
        orders.put(orderId, new OrderRecord(plan.failed(), current.createdAt(), plan.failedAt(), current.enqueuedAt()));
        activeLocks.remove(ActiveOrderKey.of(plan.current()));
        audits.add(plan.audit());
        return TransactionOutcome.applied();
    }

    /** AP-015 Order guard per kind (data model §5). */
    private static boolean closable(Order stored, ClosurePlan plan) {
        if (stored.status() != OrderStatus.CREATED || stored.quarantinedAt() != null) {
            return false;
        }
        return switch (plan.kind()) {
            case REJECT -> stored.paymentAttempt() != null && sameAttempt(stored, plan.current());
            case FAIL_PROCESSING -> sameAttempt(stored, plan.current());
            case EXPIRE -> !stored.reservation().expiresAt().isAfter(plan.now());
        };
    }

    /** PaymentAttempt identity equal to the one read, including its absence. */
    private static boolean sameAttempt(Order stored, Order read) {
        String storedId = stored.paymentAttempt() == null ? null : stored.paymentAttempt().paymentAttemptId();
        String readId = read.paymentAttempt() == null ? null : read.paymentAttempt().paymentAttemptId();
        return Objects.equals(storedId, readId);
    }

    /** Every Ticket of the Order exists, belongs to it and is in one of the expected source states. */
    private void checkTickets(Order order, List<TicketState> expectedStates, List<ItemFailure> failed) {
        Map<String, Ticket> eventTickets = ticketsOf(order.eventId());
        for (String ticketId : order.ticketIds()) {
            Ticket ticket = eventTickets.get(ticketId);
            if (ticket == null) {
                failed.add(ItemFailure.ticket(FailedItem.TICKET_MISSING, ticketId));
            } else if (!expectedStates.contains(ticket.state()) || !order.orderId().equals(ticket.orderId())) {
                failed.add(ItemFailure.ticket(FailedItem.TICKET_STATE, ticketId));
            }
        }
    }

    /** Lock removal condition: absent or owned by this Order. */
    private void checkLock(Order order, List<ItemFailure> failed) {
        String owner = activeLocks.get(ActiveOrderKey.of(order));
        if (owner != null && !owner.equals(order.orderId())) {
            failed.add(ItemFailure.of(FailedItem.ACTIVE_ORDER_LOCK));
        }
    }

    private void checkAudit(AuditRecord audit, List<ItemFailure> failed) {
        if (audits.contains(audit)) {
            failed.add(ItemFailure.of(FailedItem.AUDIT));
        }
    }

    private Flux<String> query(Operation operation, java.util.function.Predicate<OrderRecord> member,
            Comparator<OrderRecord> order) {
        return guarded(operation, () -> {
            synchronized (monitor) {
                return orders.values().stream().filter(member).sorted(order)
                        .map(record -> record.order().orderId()).toList();
            }
        }).flatMapMany(Flux::fromIterable);
    }

    private static ProvisioningSnapshot snapshot(ProvisioningSnapshot current, Event event, int provisionedBatches,
            String leaseOwner, Instant leaseUntil, Instant lastProgressAt, int republishCount, Instant enabledAt,
            Instant failedAt, Instant ticketsPurgedAt) {
        return new ProvisioningSnapshot(event, current.createdAt(), provisionedBatches, enabledAt, failedAt, leaseOwner,
                leaseUntil, lastProgressAt, republishCount, ticketsPurgedAt);
    }

    // ----------------------------------------------------------------- fault injection

    private <T> Mono<T> guarded(Operation operation, Callable<T> body) {
        return Mono.defer(() -> {
            waiting.computeIfAbsent(operation, ignored -> new AtomicInteger()).incrementAndGet();
            Sinks.Empty<Void> gate = gates.get(operation);
            Mono<Void> wait = gate == null ? Mono.empty() : gate.asMono().publishOn(Schedulers.parallel());
            return wait.then(Mono.fromCallable(() -> {
                calls.computeIfAbsent(operation, ignored -> new AtomicInteger()).incrementAndGet();
                Runnable hook = hooks.remove(operation);
                if (hook != null) {
                    hook.run();
                }
                if (consumeFailure(operation)) {
                    throw new IllegalStateException("simulated transient failure of " + operation);
                }
                return body.call();
            }));
        });
    }

    private boolean consumeFailure(Operation operation) {
        synchronized (failures) {
            Integer remaining = failures.get(operation);
            if (remaining == null || remaining == 0) {
                return false;
            }
            failures.put(operation, remaining - 1);
            return true;
        }
    }

    private TransactionOutcome scriptedOutcome(Operation operation) {
        Deque<TransactionOutcome> queue = scripted.get(operation);
        return queue == null ? null : queue.poll();
    }

    /** The next {@code times} invocations of {@code operation} fail with a simulated transient error. */
    public void failNext(Operation operation, int times) {
        synchronized (failures) {
            failures.put(operation, times);
        }
    }

    /** The next transactions of {@code operation} return these outcomes without evaluating anything. */
    public void script(Operation operation, TransactionOutcome... outcomes) {
        synchronized (monitor) {
            scripted.computeIfAbsent(operation, ignored -> new ArrayDeque<>()).addAll(List.of(outcomes));
        }
    }

    /** Runs {@code hook} once, just before the next invocation of {@code operation} is evaluated. */
    public void beforeNext(Operation operation, Runnable hook) {
        hooks.put(operation, hook);
    }

    /** Holds every invocation of {@code operation} until {@link #release(Operation)} (non-blocking gate). */
    public void hold(Operation operation) {
        gates.put(operation, Sinks.empty());
    }

    public void release(Operation operation) {
        Sinks.Empty<Void> gate = gates.remove(operation);
        if (gate != null) {
            gate.tryEmitEmpty();
        }
    }

    public int waiting(Operation operation) {
        AtomicInteger count = waiting.get(operation);
        return count == null ? 0 : count.get();
    }

    public int calls(Operation operation) {
        AtomicInteger count = calls.get(operation);
        return count == null ? 0 : count.get();
    }

    // ----------------------------------------------------------------- cursors

    private static int eventCursorOffset(String cursor) {
        if (cursor == null) {
            return 0;
        }
        if (!cursor.matches("events:\\d{1,6}")) {
            throw new ValidationException("cursor", "cursor is invalid");
        }
        return Integer.parseInt(cursor.substring("events:".length()));
    }

    private static String availabilityCursorPosition(Event event, String section, String cursor) {
        if (cursor == null) {
            return null;
        }
        String[] parts = cursor.split("\\|", -1);
        String expectedSection = section == null ? "" : section;
        if (parts.length != 3 || !parts[0].equals(event.eventId()) || !parts[1].equals(expectedSection) || parts[2].isEmpty()) {
            throw new ValidationException("cursor", "cursor is invalid");
        }
        return parts[2];
    }

    private Map<String, Ticket> ticketsOf(String eventId) {
        return tickets.computeIfAbsent(eventId, ignored -> new HashMap<>());
    }

    private static String key(IdempotencyRecord record) {
        return record.ownerId() + "#" + record.idempotencyKey();
    }

    // ----------------------------------------------------------------- seeding (api role)

    /** Seeds an Event already provisioned and {@code ENABLED} with its Tickets from the definition. */
    public Event seedEnabledEvent(String eventId, String name, Instant startsAt, InventoryDefinition definition) {
        int capacity = capacityOf(definition);
        Instant createdAt = startsAt.minus(Duration.ofDays(30));
        Event event = Event.create(eventId, name, "Main venue", startsAt, capacity, definition, createdAt,
                InventoryLimits.DEPLOYED).enable(capacity);
        synchronized (monitor) {
            events.put(eventId, new ProvisioningSnapshot(event, createdAt, Math.ceilDiv(capacity, 100), createdAt, null));
            Map<String, Ticket> eventTickets = ticketsOf(eventId);
            for (TicketSeed seed : definition.validate(capacity, InventoryLimits.DEPLOYED).tickets()) {
                eventTickets.put(seed.ticketId(), Ticket.provision(eventId, seed));
            }
        }
        return event;
    }

    /** Seeds an Event in {@code PROVISIONING} without Tickets, created at {@code createdAt}. */
    public Event seedProvisioningEvent(String eventId, Instant startsAt, InventoryDefinition definition, Instant createdAt) {
        int capacity = capacityOf(definition);
        Event event = Event.create(eventId, "Provisioning", "Main venue", startsAt, capacity, definition, createdAt,
                InventoryLimits.DEPLOYED);
        synchronized (monitor) {
            events.put(eventId, new ProvisioningSnapshot(event, createdAt, 0, null, null, null, null, createdAt, 0, null));
        }
        return event;
    }

    public void seedSnapshot(ProvisioningSnapshot snapshot) {
        synchronized (monitor) {
            events.put(snapshot.event().eventId(), snapshot);
        }
    }

    public void setTicket(String eventId, String ticketId, TicketState state, String orderId) {
        synchronized (monitor) {
            Ticket current = ticketsOf(eventId).get(ticketId);
            ticketsOf(eventId).put(ticketId,
                    new Ticket(eventId, ticketId, current.section(), current.row(), current.seat(), state, orderId));
        }
    }

    public void removeTicket(String eventId, String ticketId) {
        synchronized (monitor) {
            ticketsOf(eventId).remove(ticketId);
        }
    }

    public void putOrder(OrderRecord record) {
        synchronized (monitor) {
            orders.put(record.order().orderId(), record);
        }
    }

    public void removeOrder(String orderId) {
        synchronized (monitor) {
            orders.remove(orderId);
        }
    }

    public void removeEvent(String eventId) {
        synchronized (monitor) {
            events.remove(eventId);
        }
    }

    public void putActiveLock(ActiveOrderKey key, String orderId) {
        synchronized (monitor) {
            activeLocks.put(key, orderId);
        }
    }

    public void putPurchaseIdempotency(IdempotencyRecord record) {
        synchronized (monitor) {
            purchaseIdempotency.put(key(record), record);
        }
    }

    /** Next reservation transactions return these outcomes without evaluating anything. */
    public void scriptReservations(TransactionOutcome... outcomes) {
        synchronized (monitor) {
            scriptedReservations.addAll(List.of(outcomes));
        }
    }

    /** Next "Fail (enqueue)" transactions return these outcomes without evaluating anything. */
    public void scriptEnqueueFailures(TransactionOutcome... outcomes) {
        synchronized (monitor) {
            scriptedEnqueueFailures.addAll(List.of(outcomes));
        }
    }

    public void failMarkEnqueued(boolean fails) {
        this.markEnqueuedFails = fails;
    }

    /** Holds every reservation transaction until {@link #releaseReservations()} (non-blocking gate). */
    public void holdReservations() {
        reservationGate = Sinks.empty();
    }

    public void releaseReservations() {
        Sinks.Empty<Void> gate = reservationGate;
        reservationGate = null;
        gate.tryEmitEmpty();
    }

    public int waitingReservations() {
        return waitingReservations.get();
    }

    public int reservationAttempts() {
        return reservationAttempts.get();
    }

    public int countQueries() {
        return countQueries.get();
    }

    // ----------------------------------------------------------------- inspection

    public Ticket ticket(String eventId, String ticketId) {
        synchronized (monitor) {
            return ticketsOf(eventId).get(ticketId);
        }
    }

    public List<Ticket> tickets(String eventId) {
        synchronized (monitor) {
            return List.copyOf(ticketsOf(eventId).values());
        }
    }

    public Optional<OrderRecord> order(String orderId) {
        synchronized (monitor) {
            return Optional.ofNullable(orders.get(orderId));
        }
    }

    public List<OrderRecord> orders() {
        synchronized (monitor) {
            return List.copyOf(orders.values());
        }
    }

    public Optional<String> activeLock(String customerId, String eventId) {
        synchronized (monitor) {
            return Optional.ofNullable(activeLocks.get(new ActiveOrderKey(customerId, eventId)));
        }
    }

    public Optional<IdempotencyRecord> purchaseIdempotency(String customerId, String idempotencyKey) {
        synchronized (monitor) {
            return Optional.ofNullable(purchaseIdempotency.get(customerId + "#" + idempotencyKey));
        }
    }

    public int purchaseIdempotencyCount() {
        synchronized (monitor) {
            return purchaseIdempotency.size();
        }
    }

    public int eventCount() {
        synchronized (monitor) {
            return events.size();
        }
    }

    public int eventIdempotencyCount() {
        synchronized (monitor) {
            return eventIdempotency.size();
        }
    }

    public Optional<ProvisioningSnapshot> snapshot(String eventId) {
        synchronized (monitor) {
            return Optional.ofNullable(events.get(eventId));
        }
    }

    /** Ticket identifiers of every batch write, in order (AP-002). */
    public List<List<String>> writtenBatches() {
        synchronized (monitor) {
            return List.copyOf(writtenBatches);
        }
    }

    public List<AuditRecord> audits() {
        synchronized (monitor) {
            return List.copyOf(audits);
        }
    }

    private static int capacityOf(InventoryDefinition definition) {
        return definition.sections().stream()
                .flatMap(section -> section.rows().stream())
                .mapToInt(InventoryDefinition.Row::seats)
                .sum();
    }
}
