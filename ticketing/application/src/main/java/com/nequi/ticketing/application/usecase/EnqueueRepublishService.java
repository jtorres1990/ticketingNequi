package com.nequi.ticketing.application.usecase;

import com.nequi.ticketing.application.port.in.CycleRequest;
import com.nequi.ticketing.application.port.in.CycleResult;
import com.nequi.ticketing.application.port.in.ItemOutcome;
import com.nequi.ticketing.application.port.in.RepublishPendingOrdersUseCase;
import com.nequi.ticketing.application.port.out.Clock;
import com.nequi.ticketing.application.port.out.OrderLifecycleStore;
import com.nequi.ticketing.application.port.out.OrderProcessingRequested;
import com.nequi.ticketing.application.port.out.OrderQueuePublisher;
import com.nequi.ticketing.application.port.out.OrderReader;
import com.nequi.ticketing.application.port.out.OrderRecord;
import com.nequi.ticketing.application.port.out.PublishResult;
import com.nequi.ticketing.application.port.out.PublisherAvailability;
import com.nequi.ticketing.domain.event.ShardingPolicy;
import com.nequi.ticketing.domain.order.Order;
import com.nequi.ticketing.domain.order.Order.OrderStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * CMP-023 Enqueue republish sweep (FR-005, ALT-006; ADR-026): with the publication circuit available,
 * queries the {@code PENDQ#} shards of {@code GSI4} for Orders created more than 30 s ago (AP-028),
 * re-reads each one (AP-010) and, while it is {@code CREATED} without {@code enqueuedAt}, without
 * quarantine and with at least the payment cutoff (15 s) left before {@code expiresAt}, republishes
 * MSG-001 (publisher {@code sweep}) and marks {@code enqueuedAt} (AP-011, best effort). An Order with less
 * time left is left to expiration. It is not a business transition: no audit (ADR-031).
 */
public final class EnqueueRepublishService implements RepublishPendingOrdersUseCase {

    private final OrderReader orderReader;
    private final OrderLifecycleStore lifecycleStore;
    private final OrderQueuePublisher orderPublisher;
    private final Clock clock;
    private final WorkerUseCaseSettings settings;

    public EnqueueRepublishService(
            OrderReader orderReader,
            OrderLifecycleStore lifecycleStore,
            OrderQueuePublisher orderPublisher,
            Clock clock,
            WorkerUseCaseSettings settings) {
        this.orderReader = Objects.requireNonNull(orderReader, "orderReader");
        this.lifecycleStore = Objects.requireNonNull(lifecycleStore, "lifecycleStore");
        this.orderPublisher = Objects.requireNonNull(orderPublisher, "orderPublisher");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override
    public Mono<CycleResult> republishPending(CycleRequest request) {
        return Mono.defer(() -> {
            Objects.requireNonNull(request, "request");
            if (orderPublisher.availability() instanceof PublisherAvailability.Unavailable) {
                return Mono.just(CycleResult.skippedCycle());
            }
            Instant createdBefore = clock.now().minus(settings.republishAge());
            Flux<Optional<String>> candidates = Flux.fromIterable(request.shards(ShardingPolicy.PENDING_ENQUEUE_SHARDS))
                    .concatMap(shard -> CycleSupport.guarded(orderReader.findPendingEnqueue(shard, createdBefore)));
            return CycleSupport.summarize(CycleSupport.process(candidates,
                    orderId -> republishOne(orderId, request.correlationId()), settings.republishConcurrency()));
        });
    }

    private Mono<ItemOutcome> republishOne(String orderId, String correlationId) {
        return orderReader.findById(orderId).flatMap(record -> {
            if (!eligible(record, clock.now())) {
                return Mono.just(ItemOutcome.NOT_APPLICABLE);
            }
            Order order = record.order();
            OrderProcessingRequested message = new OrderProcessingRequested(order.orderId(), order.eventId(),
                    order.reservation().reservedAt(), correlationId, OrderProcessingRequested.Publisher.SWEEP);
            return orderPublisher.publish(message)
                    .defaultIfEmpty(PublishResult.FAILED)
                    .onErrorReturn(PublishResult.FAILED)
                    .flatMap(result -> result == PublishResult.PUBLISHED
                            ? lifecycleStore.markEnqueued(orderId, clock.now())
                                    .onErrorReturn(Boolean.FALSE)
                                    .defaultIfEmpty(Boolean.FALSE)
                                    .thenReturn(ItemOutcome.REPUBLISHED)
                            : Mono.just(ItemOutcome.FAILED));
        });
    }

    /** ADR-026 eligibility on the strongly consistent read; the index entry may be stale. */
    private boolean eligible(OrderRecord record, Instant now) {
        Order order = record.order();
        return order.status() == OrderStatus.CREATED
                && !record.enqueued()
                && order.quarantinedAt() == null
                && record.createdAt().isBefore(now.minus(settings.republishAge()))
                && Duration.between(now, order.reservation().expiresAt()).compareTo(Order.PAYMENT_CUTOFF) >= 0;
    }
}
