package com.nequi.ticketing.application.testdouble;

import com.nequi.ticketing.application.port.out.AvailableTicket;
import com.nequi.ticketing.application.port.out.AvailableTicketPage;
import com.nequi.ticketing.application.port.out.EnabledEventPage;
import com.nequi.ticketing.application.port.out.EnqueueFailurePlan;
import com.nequi.ticketing.application.port.out.EventCatalog;
import com.nequi.ticketing.application.port.out.FailedItem;
import com.nequi.ticketing.application.port.out.IdempotencyRecord;
import com.nequi.ticketing.application.port.out.IdempotencyStore;
import com.nequi.ticketing.application.port.out.ItemFailure;
import com.nequi.ticketing.application.port.out.NewEventPlan;
import com.nequi.ticketing.application.port.out.OrderLifecycleStore;
import com.nequi.ticketing.application.port.out.OrderReader;
import com.nequi.ticketing.application.port.out.OrderRecord;
import com.nequi.ticketing.application.port.out.ProvisioningSnapshot;
import com.nequi.ticketing.application.port.out.QuarantinePlan;
import com.nequi.ticketing.application.port.out.ReservationPlan;
import com.nequi.ticketing.application.port.out.TicketInventory;
import com.nequi.ticketing.application.port.out.TransactionOutcome;
import com.nequi.ticketing.domain.audit.AuditRecord;
import com.nequi.ticketing.domain.error.ValidationException;
import com.nequi.ticketing.domain.event.Event;
import com.nequi.ticketing.domain.event.InventoryDefinition;
import com.nequi.ticketing.domain.event.InventoryDefinition.TicketSeed;
import com.nequi.ticketing.domain.event.InventoryLimits;
import com.nequi.ticketing.domain.order.ActiveOrderKey;
import com.nequi.ticketing.domain.order.Order;
import com.nequi.ticketing.domain.order.Order.OrderStatus;
import com.nequi.ticketing.domain.ticket.Ticket;
import com.nequi.ticketing.domain.ticket.Ticket.TicketState;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/**
 * In-memory double of the persistence ports (ADR-038). Every write evaluates exactly the items and
 * conditions of {@code ticketing.data-model.v2.md} §5 atomically under one monitor and reports the
 * reason per failed item, so it reproduces the single winner per Ticket, the active Order lock, the
 * quarantine (lock retained, no Ticket written) and the payment reversal mark carried by the Order.
 */
public final class InMemoryTicketingStore
        implements EventCatalog, TicketInventory, OrderLifecycleStore, OrderReader, IdempotencyStore {

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

    // ----------------------------------------------------------------- EventCatalog

    @Override
    public Mono<TransactionOutcome> create(NewEventPlan plan) {
        return Mono.fromCallable(() -> {
            synchronized (monitor) {
                List<ItemFailure> failures = new ArrayList<>();
                if (events.containsKey(plan.event().eventId())) {
                    failures.add(ItemFailure.of(FailedItem.EVENT));
                }
                if (eventIdempotency.containsKey(key(plan.idempotency()))) {
                    failures.add(ItemFailure.of(FailedItem.IDEMPOTENCY_RECORD));
                }
                if (audits.contains(plan.audit())) {
                    failures.add(ItemFailure.of(FailedItem.AUDIT));
                }
                if (!failures.isEmpty()) {
                    return TransactionOutcome.cancelled(failures);
                }
                events.put(plan.event().eventId(), new ProvisioningSnapshot(plan.event(), plan.createdAt(), 0, null, null));
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
        return Mono.fromCallable(() -> {
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
                        .filter(event -> event.provisioningStatus() == Event.ProvisioningStatus.ENABLED)
                        .filter(event -> event.startsAt().isAfter(now))
                        .sorted(Comparator.comparing(Event::startsAt).thenComparing(Event::eventId))
                        .toList();
                List<Event> page = enabled.stream().skip(offset).limit(limit).toList();
                String next = offset + page.size() < enabled.size() ? "events:" + (offset + page.size()) : null;
                return new EnabledEventPage(page, next);
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

    // ----------------------------------------------------------------- OrderLifecycleStore

    @Override
    public Mono<TransactionOutcome> reserve(ReservationPlan plan) {
        waitingReservations.incrementAndGet();
        Sinks.Empty<Void> gate = reservationGate;
        Mono<Void> wait = gate == null ? Mono.empty() : gate.asMono().publishOn(Schedulers.parallel());
        return wait.then(Mono.fromCallable(() -> {
            reservationAttempts.incrementAndGet();
            synchronized (monitor) {
                TransactionOutcome scripted = scriptedReservations.poll();
                return scripted != null ? scripted : applyReservation(plan);
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
                orders.put(orderId, new OrderRecord(current.order(), current.createdAt(), current.updatedAt(), enqueuedAt));
                return true;
            }
        });
    }

    @Override
    public Mono<TransactionOutcome> failEnqueue(EnqueueFailurePlan plan) {
        return Mono.fromCallable(() -> {
            synchronized (monitor) {
                TransactionOutcome scripted = scriptedEnqueueFailures.poll();
                return scripted != null ? scripted : applyEnqueueFailure(plan);
            }
        });
    }

    @Override
    public Mono<TransactionOutcome> quarantine(QuarantinePlan plan) {
        return Mono.fromCallable(() -> {
            synchronized (monitor) {
                OrderRecord current = orders.get(plan.current().orderId());
                if (current == null || current.order().status() != OrderStatus.CREATED
                        || current.order().quarantinedAt() != null) {
                    return TransactionOutcome.cancelled(List.of(ItemFailure.of(FailedItem.ORDER)));
                }
                orders.put(current.order().orderId(), new OrderRecord(
                        plan.quarantined(), current.createdAt(), plan.quarantined().quarantinedAt(), current.enqueuedAt()));
                audits.add(plan.audit());
                return TransactionOutcome.applied();
            }
        });
    }

    // ----------------------------------------------------------------- OrderReader / IdempotencyStore

    @Override
    public Mono<OrderRecord> findById(String orderId) {
        return Mono.fromCallable(() -> {
            synchronized (monitor) {
                return Optional.ofNullable(orders.get(orderId));
            }
        }).flatMap(Mono::justOrEmpty);
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
        List<ItemFailure> failures = new ArrayList<>();
        for (String ticketId : order.ticketIds()) {
            Ticket ticket = eventTickets.get(ticketId);
            if (ticket == null || !ticket.eventId().equals(order.eventId())) {
                failures.add(ItemFailure.ticket(FailedItem.TICKET_MISSING, ticketId));
            } else if (ticket.state() != TicketState.AVAILABLE) {
                failures.add(ItemFailure.ticket(FailedItem.TICKET_STATE, ticketId));
            }
        }
        if (orders.containsKey(order.orderId())) {
            failures.add(ItemFailure.of(FailedItem.ORDER));
        }
        if (purchaseIdempotency.containsKey(key(plan.idempotency()))) {
            failures.add(ItemFailure.of(FailedItem.IDEMPOTENCY_RECORD));
        }
        if (audits.contains(plan.audit())) {
            failures.add(ItemFailure.of(FailedItem.AUDIT));
        }
        if (activeLocks.containsKey(plan.activeOrderKey())) {
            failures.add(ItemFailure.of(FailedItem.ACTIVE_ORDER_LOCK));
        }
        if (!failures.isEmpty()) {
            return TransactionOutcome.cancelled(failures);
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
        List<ItemFailure> failures = new ArrayList<>();
        if (current == null || current.order().status() != OrderStatus.CREATED || current.order().paymentAttempt() != null) {
            failures.add(ItemFailure.of(FailedItem.ORDER));
        }
        Map<String, Ticket> eventTickets = ticketsOf(plan.current().eventId());
        for (String ticketId : plan.current().ticketIds()) {
            Ticket ticket = eventTickets.get(ticketId);
            if (ticket == null) {
                failures.add(ItemFailure.ticket(FailedItem.TICKET_MISSING, ticketId));
            } else if ((ticket.state() != TicketState.RESERVED && ticket.state() != TicketState.PENDING_CONFIRMATION)
                    || !orderId.equals(ticket.orderId())) {
                failures.add(ItemFailure.ticket(FailedItem.TICKET_STATE, ticketId));
            }
        }
        ActiveOrderKey lock = ActiveOrderKey.of(plan.current());
        String lockOwner = activeLocks.get(lock);
        if (lockOwner != null && !lockOwner.equals(orderId)) {
            failures.add(ItemFailure.of(FailedItem.ACTIVE_ORDER_LOCK));
        }
        if (audits.contains(plan.audit())) {
            failures.add(ItemFailure.of(FailedItem.AUDIT));
        }
        if (!failures.isEmpty()) {
            return TransactionOutcome.cancelled(failures);
        }
        for (String ticketId : plan.current().ticketIds()) {
            eventTickets.put(ticketId, eventTickets.get(ticketId).release(orderId));
        }
        orders.put(orderId, new OrderRecord(plan.failed(), current.createdAt(), plan.failedAt(), current.enqueuedAt()));
        activeLocks.remove(lock);
        audits.add(plan.audit());
        return TransactionOutcome.applied();
    }

    // ----------------------------------------------------------------- cursors

    private static int eventCursorOffset(String cursor) {
        if (cursor == null) {
            return 0;
        }
        if (!cursor.matches("events:\\d{1,6}")) {
            throw new ValidationException("cursor is invalid");
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
            throw new ValidationException("cursor is invalid");
        }
        return parts[2];
    }

    private Map<String, Ticket> ticketsOf(String eventId) {
        return tickets.computeIfAbsent(eventId, ignored -> new HashMap<>());
    }

    private static String key(IdempotencyRecord record) {
        return record.ownerId() + "#" + record.idempotencyKey();
    }

    // ----------------------------------------------------------------- seeding and fault injection

    /** Seeds an Event already provisioned and {@code ENABLED} with its Tickets from the definition. */
    public Event seedEnabledEvent(String eventId, String name, Instant startsAt, InventoryDefinition definition) {
        int capacity = definition.sections().stream()
                .flatMap(section -> section.rows().stream())
                .mapToInt(InventoryDefinition.Row::seats)
                .sum();
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

    public void putOrder(OrderRecord record) {
        synchronized (monitor) {
            orders.put(record.order().orderId(), record);
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

    public List<AuditRecord> audits() {
        synchronized (monitor) {
            return List.copyOf(audits);
        }
    }
}
