package com.nequi.ticketing.application.usecase;

import com.nequi.ticketing.application.error.RequestRejectedException;
import com.nequi.ticketing.application.port.in.OrderView;
import com.nequi.ticketing.application.port.in.PurchaseResult;
import com.nequi.ticketing.application.port.in.StartPurchaseCommand;
import com.nequi.ticketing.application.port.in.StartPurchaseUseCase;
import com.nequi.ticketing.application.port.out.Clock;
import com.nequi.ticketing.application.port.out.EnqueueFailurePlan;
import com.nequi.ticketing.application.port.out.EventCatalog;
import com.nequi.ticketing.application.port.out.FailedItem;
import com.nequi.ticketing.application.port.out.IdGenerator;
import com.nequi.ticketing.application.port.out.IdempotencyRecord;
import com.nequi.ticketing.application.port.out.IdempotencyStore;
import com.nequi.ticketing.application.port.out.OrderLifecycleStore;
import com.nequi.ticketing.application.port.out.OrderProcessingRequested;
import com.nequi.ticketing.application.port.out.OrderQueuePublisher;
import com.nequi.ticketing.application.port.out.OrderReader;
import com.nequi.ticketing.application.port.out.OrderRecord;
import com.nequi.ticketing.application.port.out.PublishResult;
import com.nequi.ticketing.application.port.out.PublisherAvailability;
import com.nequi.ticketing.application.port.out.ReservationPlan;
import com.nequi.ticketing.application.port.out.TransactionOutcome;
import com.nequi.ticketing.domain.event.Event;
import com.nequi.ticketing.domain.order.ActiveOrderKey;
import com.nequi.ticketing.domain.order.Order;
import com.nequi.ticketing.domain.order.Order.OrderStatus;
import com.nequi.ticketing.domain.order.PurchaseRequest;
import com.nequi.ticketing.domain.shared.ContentHash;
import com.nequi.ticketing.domain.shared.DomainChecks;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import reactor.core.publisher.Mono;

/**
 * CMP-005 Purchase initiation (FR-004, FR-005, FR-006, FR-010, FR-016, FR-017, FR-021, FR-022,
 * FR-024). Applies the BR-031 precedence: validation, idempotency (AP-009), Event (AP-006), past
 * Event, unknown tickets against the definition, queue publication availability, then the atomic
 * reservation (AP-008) whose cancellation is resolved by re-reading the idempotency record first
 * (ADR-023, ADR-027). After the commit it publishes MSG-001, marks {@code enqueuedAt} (AP-011) without
 * failing the purchase, and compensates a definitive enqueue failure with "Fail (enqueue)" (AP-015,
 * ADR-026). Rejections persist nothing (BR-019, HC-001).
 */
public final class PurchaseService implements StartPurchaseUseCase {

    static final String ENQUEUE_COMPENSATION_QUARANTINE_REASON = OrderQuarantine.ON_ENQUEUE_FAILURE;

    private final EventCatalog eventCatalog;
    private final OrderLifecycleStore lifecycleStore;
    private final OrderReader orderReader;
    private final IdempotencyStore idempotencyStore;
    private final OrderQueuePublisher orderPublisher;
    private final Clock clock;
    private final IdGenerator idGenerator;
    private final ApiUseCaseSettings settings;
    private final OrderQuarantine quarantine;

    public PurchaseService(
            EventCatalog eventCatalog,
            OrderLifecycleStore lifecycleStore,
            OrderReader orderReader,
            IdempotencyStore idempotencyStore,
            OrderQueuePublisher orderPublisher,
            Clock clock,
            IdGenerator idGenerator,
            ApiUseCaseSettings settings) {
        this.eventCatalog = Objects.requireNonNull(eventCatalog, "eventCatalog");
        this.lifecycleStore = Objects.requireNonNull(lifecycleStore, "lifecycleStore");
        this.orderReader = Objects.requireNonNull(orderReader, "orderReader");
        this.idempotencyStore = Objects.requireNonNull(idempotencyStore, "idempotencyStore");
        this.orderPublisher = Objects.requireNonNull(orderPublisher, "orderPublisher");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.quarantine = new OrderQuarantine(lifecycleStore, clock);
    }

    @Override
    public Mono<PurchaseResult> startPurchase(StartPurchaseCommand command) {
        return Mono.defer(() -> {
            Objects.requireNonNull(command, "command");
            PurchaseRequest request = PurchaseRequest.of(command.eventId(), command.ticketIds(), command.idempotencyKey(),
                    settings.orderRules());
            String customerId = DomainChecks.required(command.customerId(), "customerId");
            String correlationId = Objects.requireNonNull(command.correlationId(), "correlationId");
            Attempt attempt = new Attempt(customerId, request, ContentHash.purchase(request), correlationId);
            return idempotencyStore.findPurchase(customerId, request.idempotencyKey())
                    .map(Optional::of)
                    .defaultIfEmpty(Optional.empty())
                    .flatMap(existing -> existing
                            .map(record -> replay(record, attempt))
                            .orElseGet(() -> newPurchase(attempt)));
        });
    }

    private Mono<PurchaseResult> newPurchase(Attempt attempt) {
        return eventCatalog.findEvent(attempt.request().eventId())
                .filter(Event::availabilityIsVisible)
                .switchIfEmpty(Mono.error(RequestRejectedException::eventNotFound))
                .flatMap(event -> checkBeforeReserving(event, attempt).then(Mono.defer(() -> reserve(attempt))));
    }

    private Mono<Void> checkBeforeReserving(Event event, Attempt attempt) {
        return Mono.defer(() -> {
            if (event.isPastAt(clock.now())) {
                return Mono.error(RequestRejectedException.eventNotOnSale());
            }
            List<String> unknown = attempt.request().ticketIds().stream()
                    .filter(ticketId -> !event.inventoryDefinition().containsTicket(ticketId))
                    .sorted()
                    .toList();
            if (!unknown.isEmpty()) {
                return Mono.error(RequestRejectedException.unknownTickets(unknown));
            }
            if (orderPublisher.availability() instanceof PublisherAvailability.Unavailable unavailable) {
                return Mono.error(RequestRejectedException.serviceUnavailable(retryAfter(unavailable.retryAfter())));
            }
            return Mono.empty();
        });
    }

    private Mono<PurchaseResult> reserve(Attempt attempt) {
        Instant reservedAt = clock.now();
        Order order = Order.create(idGenerator.newOrderId(), attempt.customerId(), attempt.request(), reservedAt);
        IdempotencyRecord idempotency = new IdempotencyRecord(
                attempt.customerId(),
                attempt.request().idempotencyKey(),
                order.orderId(),
                attempt.requestHash(),
                reservedAt,
                reservedAt.plus(settings.idempotencyRetention()));
        ReservationPlan plan = new ReservationPlan(
                order, idempotency, ApiAudits.reservationCreated(order, attempt.correlationId()), ActiveOrderKey.of(order));
        return lifecycleStore.reserve(plan).flatMap(outcome -> switch (outcome) {
            case TransactionOutcome.Applied applied -> enqueue(order, attempt.correlationId());
            case TransactionOutcome.Cancelled cancelled -> resolveRejectedReservation(attempt, cancelled);
            case TransactionOutcome.Conflict conflict -> resolveRejectedReservation(attempt, null);
        });
    }

    /**
     * ADR-023 / ADR-027: the idempotency record prevails over every cancellation reason; then lock,
     * missing Ticket and unavailable Ticket, in that order. A persistent conflict is a 503.
     */
    private Mono<PurchaseResult> resolveRejectedReservation(Attempt attempt, TransactionOutcome.Cancelled cancelled) {
        return idempotencyStore.findPurchase(attempt.customerId(), attempt.request().idempotencyKey())
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(existing -> existing
                        .map(record -> replay(record, attempt))
                        .orElseGet(() -> Mono.error(classify(cancelled))));
    }

    private RequestRejectedException classify(TransactionOutcome.Cancelled cancelled) {
        if (cancelled != null) {
            if (cancelled.failed(FailedItem.ACTIVE_ORDER_LOCK)) {
                return RequestRejectedException.activeOrderExists();
            }
            if (cancelled.failed(FailedItem.TICKET_MISSING)) {
                return RequestRejectedException.unknownTickets(cancelled.ticketIds(FailedItem.TICKET_MISSING));
            }
            if (cancelled.failed(FailedItem.TICKET_STATE)) {
                return RequestRejectedException.ticketsUnavailable(cancelled.ticketIds(FailedItem.TICKET_STATE));
            }
        }
        return RequestRejectedException.serviceUnavailable(settings.conflictRetryAfter());
    }

    /** ADR-027 replay: same content returns the Order in its current state; different content is rejected. */
    private Mono<PurchaseResult> replay(IdempotencyRecord record, Attempt attempt) {
        if (!record.sameContent(attempt.requestHash())) {
            return Mono.error(RequestRejectedException.idempotencyKeyReused());
        }
        return orderReader.findById(record.resourceId())
                .switchIfEmpty(Mono.error(() -> new IllegalStateException("idempotency record without its Order")))
                .flatMap(current -> republishIfPending(current, attempt.correlationId()).thenReturn(current))
                .map(current -> new PurchaseResult(OrderViews.of(current), true));
    }

    /**
     * The only additional action allowed on a replay: republish MSG-001 for an Order in CREATED without
     * {@code enqueuedAt} and without quarantine, unless publication is unavailable (ADR-026, ADR-027).
     */
    private Mono<Void> republishIfPending(OrderRecord current, String correlationId) {
        Order order = current.order();
        if (order.status() != OrderStatus.CREATED || current.enqueued() || order.quarantinedAt() != null
                || orderPublisher.availability() instanceof PublisherAvailability.Unavailable) {
            return Mono.empty();
        }
        return publish(order, correlationId)
                .filter(result -> result == PublishResult.PUBLISHED)
                .flatMap(published -> markEnqueued(order))
                .then();
    }

    private Mono<PurchaseResult> enqueue(Order order, String correlationId) {
        return publish(order, correlationId).flatMap(result -> result == PublishResult.PUBLISHED
                ? markEnqueued(order).thenReturn(created(order))
                : compensate(order, correlationId));
    }

    private Mono<PublishResult> publish(Order order, String correlationId) {
        OrderProcessingRequested message = new OrderProcessingRequested(
                order.orderId(),
                order.eventId(),
                order.reservation().reservedAt(),
                correlationId,
                OrderProcessingRequested.Publisher.API);
        return orderPublisher.publish(message)
                .defaultIfEmpty(PublishResult.FAILED)
                .onErrorReturn(PublishResult.FAILED);
    }

    /** AP-011 is best effort: its failure never fails the purchase; the sweep covers it (ADR-026). */
    private Mono<Boolean> markEnqueued(Order order) {
        return lifecycleStore.markEnqueued(order.orderId(), clock.now())
                .onErrorReturn(Boolean.FALSE)
                .defaultIfEmpty(Boolean.FALSE);
    }

    /** ADR-026 compensation of a definitive enqueue failure: "Fail (enqueue)" in the same request. */
    private Mono<PurchaseResult> compensate(Order order, String correlationId) {
        Instant failedAt = clock.now();
        Order failed = order.failEnqueue();
        EnqueueFailurePlan plan = new EnqueueFailurePlan(
                order, failed, ApiAudits.enqueueFailed(failed, correlationId, failedAt), failedAt);
        return lifecycleStore.failEnqueue(plan)
                .onErrorReturn(TransactionOutcome.conflict())
                .flatMap(outcome -> switch (outcome) {
                    case TransactionOutcome.Applied applied ->
                            Mono.just(new PurchaseResult(OrderViews.of(failed, order.reservation().reservedAt(), failedAt), false));
                    case TransactionOutcome.Cancelled cancelled -> resolveRejectedCompensation(order, cancelled, correlationId);
                    case TransactionOutcome.Conflict conflict -> doubleFailure();
                });
    }

    /**
     * The compensation was cancelled by a condition. ADR-026: when the Order already left the
     * compensable state, answer with its current state. ADR-025: when the Order guard still holds and a
     * Ticket condition failed, quarantine the Order. Any other case is a double failure.
     */
    private Mono<PurchaseResult> resolveRejectedCompensation(
            Order order, TransactionOutcome.Cancelled cancelled, String correlationId) {
        return orderReader.findById(order.orderId())
                .onErrorResume(error -> Mono.empty())
                .flatMap(current -> {
                    Order state = current.order();
                    if (state.status() != OrderStatus.CREATED || state.paymentAttempt() != null
                            || state.quarantinedAt() != null) {
                        return Mono.just(new PurchaseResult(OrderViews.of(current), false));
                    }
                    if (cancelled.failed(FailedItem.TICKET_STATE) || cancelled.failed(FailedItem.TICKET_MISSING)) {
                        return quarantine(current, correlationId);
                    }
                    return doubleFailure();
                })
                .switchIfEmpty(Mono.defer(this::doubleFailure));
    }

    private Mono<PurchaseResult> quarantine(OrderRecord current, String correlationId) {
        return quarantine.apply(current.order(), ENQUEUE_COMPENSATION_QUARANTINE_REASON, ApiAudits.API_PROCESS,
                        correlationId)
                .onErrorReturn(TransactionOutcome.conflict())
                .flatMap(outcome -> outcome instanceof TransactionOutcome.Applied
                        ? Mono.just(new PurchaseResult(OrderViews.of(current), false))
                        : doubleFailure());
    }

    private Mono<PurchaseResult> doubleFailure() {
        return Mono.error(RequestRejectedException.serviceUnavailable(settings.doubleFailureRetryAfter()));
    }

    private PurchaseResult created(Order order) {
        Instant createdAt = order.reservation().reservedAt();
        OrderView view = OrderViews.of(order, createdAt, createdAt);
        return new PurchaseResult(view, false);
    }

    private Duration retryAfter(Duration remaining) {
        return remaining.compareTo(settings.minimumRetryAfter()) < 0 ? settings.minimumRetryAfter() : remaining;
    }

    private record Attempt(String customerId, PurchaseRequest request, String requestHash, String correlationId) {
    }
}
