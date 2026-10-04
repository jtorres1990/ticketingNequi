package com.nequi.ticketing.application.usecase;

import com.nequi.ticketing.application.port.in.CycleRequest;
import com.nequi.ticketing.application.port.in.CycleResult;
import com.nequi.ticketing.application.port.in.ItemOutcome;
import com.nequi.ticketing.application.port.in.ReversePaymentsUseCase;
import com.nequi.ticketing.application.port.out.CancellationOutcome;
import com.nequi.ticketing.application.port.out.CancellationOutcome.CancellationStatus;
import com.nequi.ticketing.application.port.out.Clock;
import com.nequi.ticketing.application.port.out.OrderLifecycleStore;
import com.nequi.ticketing.application.port.out.OrderReader;
import com.nequi.ticketing.application.port.out.PaymentGateway;
import com.nequi.ticketing.application.port.out.ReversalCompletionPlan;
import com.nequi.ticketing.application.port.out.ReversalExhaustionPlan;
import com.nequi.ticketing.application.port.out.TransactionOutcome;
import com.nequi.ticketing.domain.audit.AuditRecord.Actor;
import com.nequi.ticketing.domain.audit.AuditRecord.ActorType;
import com.nequi.ticketing.domain.event.ShardingPolicy;
import com.nequi.ticketing.domain.order.Order;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * CMP-024 Payment reversal (FR-023, BR-028, BR-034, ALT-008, ERR-016, ERR-017; ADR-025, FG-003):
 * queries the {@code REVERSAL#} shards of {@code GSI3} (AP-029), re-reads each Order and, when its
 * reversal is due, requests one idempotent cancellation of the {@code paymentAttemptId} (API-102). A
 * confirmed cancellation completes the reversal with {@code PAYMENT_REVERSAL_CONFIRMED} (AP-030); any
 * other result reschedules it with the persisted backoff, and the tenth failure exhausts it with
 * {@code PAYMENT_REVERSAL_EXHAUSTED} for manual review. The Order keeps its terminal state.
 */
public final class PaymentReversalService implements ReversePaymentsUseCase {

    private final OrderReader orderReader;
    private final OrderLifecycleStore lifecycleStore;
    private final PaymentGateway paymentGateway;
    private final Clock clock;
    private final WorkerUseCaseSettings settings;
    private final Actor actor;

    public PaymentReversalService(
            OrderReader orderReader,
            OrderLifecycleStore lifecycleStore,
            PaymentGateway paymentGateway,
            Clock clock,
            WorkerUseCaseSettings settings) {
        this.orderReader = Objects.requireNonNull(orderReader, "orderReader");
        this.lifecycleStore = Objects.requireNonNull(lifecycleStore, "lifecycleStore");
        this.paymentGateway = Objects.requireNonNull(paymentGateway, "paymentGateway");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.actor = new Actor(ActorType.WORKER, settings.workerId());
    }

    @Override
    public Mono<CycleResult> reverseDue(CycleRequest request) {
        return Mono.defer(() -> {
            Objects.requireNonNull(request, "request");
            Instant now = clock.now();
            Flux<Optional<String>> candidates = Flux.fromIterable(request.shards(ShardingPolicy.REVERSAL_SHARDS))
                    .concatMap(shard -> CycleSupport.guarded(orderReader.findDueReversals(shard, now)));
            return CycleSupport.summarize(CycleSupport.process(candidates,
                    orderId -> reverseOne(orderId, request.correlationId()), settings.reversalConcurrency()));
        });
    }

    private Mono<ItemOutcome> reverseOne(String orderId, String correlationId) {
        return orderReader.findById(orderId).flatMap(record -> {
            Order order = record.order();
            if (order.reversalPlan() == null || !order.reversalPlan().dueAt(clock.now())) {
                return Mono.just(ItemOutcome.NOT_APPLICABLE);
            }
            return paymentGateway.cancel(order.reversalPlan().paymentAttemptId())
                    .defaultIfEmpty(new CancellationOutcome.DependencyUnavailable("no result"))
                    .onErrorResume(error -> Mono.just(new CancellationOutcome.DependencyUnavailable("gateway error")))
                    .flatMap(outcome -> outcome instanceof CancellationOutcome.Cancelled cancelled
                            ? complete(order, cancelled.status(), correlationId)
                            : reschedule(order, correlationId));
        });
    }

    private Mono<ItemOutcome> complete(Order order, CancellationStatus status, String correlationId) {
        Instant now = clock.now();
        Order completed = order.completeReversal(now);
        ReversalCompletionPlan plan = new ReversalCompletionPlan(order, completed,
                WorkerAudits.reversalConfirmed(completed, status, actor, correlationId, now));
        return lifecycleStore.completeReversal(plan).map(outcome -> switch (outcome) {
            case TransactionOutcome.Applied applied -> ItemOutcome.REVERSAL_CONFIRMED;
            case TransactionOutcome.Cancelled cancelled -> ItemOutcome.NOT_APPLICABLE;
            case TransactionOutcome.Conflict conflict -> ItemOutcome.FAILED;
        });
    }

    private Mono<ItemOutcome> reschedule(Order order, String correlationId) {
        Instant now = clock.now();
        Order next = order.rescheduleReversal(now);
        if (next.reversalPlan().exhausted()) {
            ReversalExhaustionPlan plan = new ReversalExhaustionPlan(order, next,
                    WorkerAudits.reversalExhausted(next, actor, correlationId, now));
            return lifecycleStore.exhaustReversal(plan).map(outcome -> switch (outcome) {
                case TransactionOutcome.Applied applied -> ItemOutcome.REVERSAL_EXHAUSTED;
                case TransactionOutcome.Cancelled cancelled -> ItemOutcome.NOT_APPLICABLE;
                case TransactionOutcome.Conflict conflict -> ItemOutcome.FAILED;
            });
        }
        return lifecycleStore.rescheduleReversal(order.orderId(), order.reversalPlan().attempts(), next.reversalPlan())
                .defaultIfEmpty(Boolean.FALSE)
                .map(rescheduled -> rescheduled ? ItemOutcome.REVERSAL_RESCHEDULED : ItemOutcome.NOT_APPLICABLE);
    }
}
