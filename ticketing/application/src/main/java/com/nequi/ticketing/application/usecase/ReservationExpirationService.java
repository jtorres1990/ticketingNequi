package com.nequi.ticketing.application.usecase;

import com.nequi.ticketing.application.port.in.CycleRequest;
import com.nequi.ticketing.application.port.in.CycleResult;
import com.nequi.ticketing.application.port.in.ExpireReservationsUseCase;
import com.nequi.ticketing.application.port.in.ItemOutcome;
import com.nequi.ticketing.application.port.out.Clock;
import com.nequi.ticketing.application.port.out.ClosurePlan;
import com.nequi.ticketing.application.port.out.OrderLifecycleStore;
import com.nequi.ticketing.application.port.out.OrderReader;
import com.nequi.ticketing.application.port.out.TransactionOutcome;
import com.nequi.ticketing.domain.audit.AuditRecord.Actor;
import com.nequi.ticketing.domain.audit.AuditRecord.ActorType;
import com.nequi.ticketing.domain.event.ShardingPolicy;
import com.nequi.ticketing.domain.order.Order;
import com.nequi.ticketing.domain.order.Order.OrderStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * CMP-008 Reservation expiration (FR-011, BR-002, BR-011, BR-030, VAL-003, ST-002, ST-010; ADR-028):
 * queries the {@code RESV#} shards of {@code GSI3} (AP-016), re-reads each candidate with strong
 * consistency (AP-010) and, while it is {@code CREATED}, not quarantined and {@code expiresAt <= now},
 * executes "Expire" (AP-015) releasing every Ticket, removing the active Order lock and marking the
 * payment reversal when the Order has a PaymentAttempt (FG-003). A cancellation by a Ticket condition
 * with the Order guard satisfied quarantines the Order (AP-031, ADR-025). A Reservation that has not
 * expired is never touched (AC-009).
 */
public final class ReservationExpirationService implements ExpireReservationsUseCase {

    private final OrderReader orderReader;
    private final OrderLifecycleStore lifecycleStore;
    private final Clock clock;
    private final WorkerUseCaseSettings settings;
    private final OrderQuarantine quarantine;
    private final Actor actor;

    public ReservationExpirationService(
            OrderReader orderReader,
            OrderLifecycleStore lifecycleStore,
            Clock clock,
            WorkerUseCaseSettings settings) {
        this.orderReader = Objects.requireNonNull(orderReader, "orderReader");
        this.lifecycleStore = Objects.requireNonNull(lifecycleStore, "lifecycleStore");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.quarantine = new OrderQuarantine(lifecycleStore, clock);
        this.actor = new Actor(ActorType.WORKER, settings.workerId());
    }

    @Override
    public Mono<CycleResult> expireDue(CycleRequest request) {
        return Mono.defer(() -> {
            Objects.requireNonNull(request, "request");
            Instant now = clock.now();
            Flux<Optional<String>> candidates = Flux.fromIterable(request.shards(ShardingPolicy.RESERVATION_SHARDS))
                    .concatMap(shard -> CycleSupport.guarded(orderReader.findDueReservations(shard, now)));
            return CycleSupport.summarize(CycleSupport.process(candidates,
                    orderId -> expireOne(orderId, request.correlationId()), settings.expirationConcurrency()));
        });
    }

    private Mono<ItemOutcome> expireOne(String orderId, String correlationId) {
        return orderReader.findById(orderId).flatMap(record -> {
            Order order = record.order();
            Instant now = clock.now();
            if (order.status() != OrderStatus.CREATED || order.quarantinedAt() != null
                    || order.reservation().expiresAt().isAfter(now)) {
                return Mono.just(ItemOutcome.NOT_APPLICABLE);
            }
            Order expired = order.expire(now);
            ClosurePlan plan = new ClosurePlan(ClosurePlan.Kind.EXPIRE, order, expired, null,
                    WorkerAudits.reservationExpired(expired, false, actor, correlationId, now), now);
            return lifecycleStore.close(plan).flatMap(outcome -> switch (outcome) {
                case TransactionOutcome.Applied applied -> Mono.just(ItemOutcome.EXPIRED);
                case TransactionOutcome.Cancelled cancelled -> quarantineIfInconsistent(orderId, cancelled, correlationId);
                case TransactionOutcome.Conflict conflict -> Mono.just(ItemOutcome.FAILED);
            });
        });
    }

    private Mono<ItemOutcome> quarantineIfInconsistent(String orderId, TransactionOutcome.Cancelled cancelled,
            String correlationId) {
        return orderReader.findById(orderId)
                .filter(reread -> OrderQuarantine.applies(cancelled, reread))
                .flatMap(reread -> quarantine.apply(reread.order(), OrderQuarantine.ON_EXPIRATION, actor, correlationId))
                .map(outcome -> switch (outcome) {
                    case TransactionOutcome.Applied applied -> ItemOutcome.QUARANTINED;
                    case TransactionOutcome.Cancelled notApplied -> ItemOutcome.NOT_APPLICABLE;
                    case TransactionOutcome.Conflict conflict -> ItemOutcome.FAILED;
                });
    }
}
