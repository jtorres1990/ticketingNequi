package com.nequi.ticketing.application.usecase;

import com.nequi.ticketing.application.port.in.DispositionReason;
import com.nequi.ticketing.application.port.in.MessageDisposition;
import com.nequi.ticketing.application.port.in.ProcessOrderCommand;
import com.nequi.ticketing.application.port.in.ProcessOrderUseCase;
import com.nequi.ticketing.application.port.out.AuthorizationOutcome;
import com.nequi.ticketing.application.port.out.Clock;
import com.nequi.ticketing.application.port.out.ClosurePlan;
import com.nequi.ticketing.application.port.out.ConfirmationPlan;
import com.nequi.ticketing.application.port.out.LateApprovalPlan;
import com.nequi.ticketing.application.port.out.OrderLifecycleStore;
import com.nequi.ticketing.application.port.out.OrderReader;
import com.nequi.ticketing.application.port.out.OrderRecord;
import com.nequi.ticketing.application.port.out.PaymentAuthorization;
import com.nequi.ticketing.application.port.out.PaymentGateway;
import com.nequi.ticketing.application.port.out.PaymentLease;
import com.nequi.ticketing.application.port.out.PaymentStartPlan;
import com.nequi.ticketing.application.port.out.TransactionOutcome;
import com.nequi.ticketing.domain.audit.AuditRecord.Actor;
import com.nequi.ticketing.domain.audit.AuditRecord.ActorType;
import com.nequi.ticketing.domain.messaging.OrderMessagePolicy;
import com.nequi.ticketing.domain.messaging.OrderMessagePolicy.Lease;
import com.nequi.ticketing.domain.messaging.OrderMessagePolicy.ProviderResult;
import com.nequi.ticketing.domain.order.Order;
import com.nequi.ticketing.domain.order.Order.OrderStatus;
import com.nequi.ticketing.domain.order.Order.PaymentOutcome;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import reactor.core.publisher.Mono;

/**
 * CMP-007 Order processing (FR-007, FR-008, FR-015, FR-016, FR-017, FR-023): applies rules 1..15 of
 * {@code ticketing.messaging.v2.md} §5.1 in their order through {@link OrderMessagePolicy}. It starts
 * the payment (AP-012), respects the PaymentAttempt lease (AP-013), authorizes with a deadline bounded
 * by {@code expiresAt} (ADR-008), confirms (AP-014) or closes with release (AP-015), records a late
 * approval (AP-032, or inside "Expire" when the Order is still {@code CREATED} and expired) and
 * quarantines an Order whose transition was cancelled by a Ticket condition (AP-031, ADR-025). A
 * condition failure is never an error: the Order is re-read and the rules are evaluated again. Store or
 * provider errors are transient: the message is retried with backoff and, on the last reception, the
 * Order is closed as {@code FAILED} with the reversal mark when the payment outcome is unknown
 * (ADR-029).
 */
public final class OrderProcessingService implements ProcessOrderUseCase {

    private final OrderReader orderReader;
    private final OrderLifecycleStore lifecycleStore;
    private final PaymentGateway paymentGateway;
    private final Clock clock;
    private final WorkerUseCaseSettings settings;
    private final OrderQuarantine quarantine;
    private final Actor actor;
    private final AtomicLong processings = new AtomicLong();

    public OrderProcessingService(
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
        this.quarantine = new OrderQuarantine(lifecycleStore, clock);
        this.actor = new Actor(ActorType.WORKER, settings.workerId());
    }

    @Override
    public Mono<MessageDisposition> process(ProcessOrderCommand command) {
        return Mono.defer(() -> {
            Objects.requireNonNull(command, "command");
            if (!command.readable()) {
                return Mono.just(MessageDisposition.poison(DispositionReason.UNREADABLE_MESSAGE));
            }
            Processing processing = new Processing(command.orderId(), command.correlationId(),
                    command.delivery().lastReception(), settings.workerId() + "/" + processings.incrementAndGet());
            return read(processing, Payment.NONE, 0)
                    .onErrorResume(error -> transientFailure(processing));
        });
    }

    // ------------------------------------------------------------------ evaluation (rules 2..15)

    private Mono<MessageDisposition> read(Processing processing, Payment payment, int evaluations) {
        return orderReader.findById(processing.orderId())
                .flatMap(record -> decide(processing, record, payment, evaluations, false))
                .switchIfEmpty(Mono.fromSupplier(() -> MessageDisposition.poison(DispositionReason.ENTITY_NOT_FOUND)));
    }

    private Mono<MessageDisposition> decide(Processing processing, OrderRecord record, Payment payment,
            int evaluations, boolean ticketConditionFailed) {
        if (evaluations > settings.maximumReevaluations()) {
            return Mono.error(new TransientFailure("too many re-evaluations"));
        }
        Instant now = clock.now();
        Order order = record.order();
        if (payment.result() == ProviderResult.APPROVED && closedWithoutConfirmation(order)) {
            // Rule 10 / ADR-008 point 5: an approval in hand for an Order already closed is recorded (AP-032).
            return recordLateApproval(processing, record, now);
        }
        OrderMessagePolicy.Snapshot snapshot = new OrderMessagePolicy.Snapshot(
                true, true, order.status(), order.quarantinedAt() != null, ticketConditionFailed,
                order.paymentAttempt() != null, order.reservation().expiresAt(),
                leaseOf(processing, record, now), payment.result(), processing.lastReception());
        return switch (OrderMessagePolicy.decide(snapshot, now, settings.orderRules().paymentCutoff())) {
            case POISON_KEEP_WITH_SHORT_VISIBILITY -> Mono.just(MessageDisposition.poison(DispositionReason.ENTITY_NOT_FOUND));
            case DELETE_NOOP -> Mono.just(MessageDisposition.delete(DispositionReason.ALREADY_TERMINAL));
            case DELETE_QUARANTINED -> Mono.just(MessageDisposition.delete(DispositionReason.QUARANTINED));
            case QUARANTINE_AND_DELETE -> quarantineAndDelete(processing, record, payment, evaluations);
            case EXPIRE_AND_DELETE -> expire(processing, record, payment, now, evaluations);
            case DELETE_WAIT_FOR_EXPIRATION -> Mono.just(MessageDisposition.delete(DispositionReason.PAYMENT_CUTOFF));
            case START_PAYMENT -> startPayment(processing, record, payment, now, evaluations);
            case POSTPONE_TO_LEASE_END -> Mono.just(MessageDisposition.postponeUntil(
                    record.paymentLease().until(), DispositionReason.LEASE_HELD_ELSEWHERE));
            case CLAIM_LEASE -> claimLease(processing, record, payment, now, evaluations);
            case AUTHORIZE_PAYMENT -> authorize(processing, record, now, evaluations);
            case CONFIRM_AND_DELETE -> confirm(processing, record, payment, now, evaluations);
            case REJECT_AND_DELETE -> reject(processing, record, payment, now, evaluations);
            case FAIL_WITHOUT_REVERSAL_AND_DELETE -> failDefinitively(processing, record, payment, now, evaluations);
            case RETRY_WITH_BACKOFF -> Mono.just(MessageDisposition.retry(DispositionReason.TRANSIENT_FAILURE));
            case FAIL_WITH_POSSIBLE_REVERSAL_AND_DLQ -> failExhausted(processing, record);
        };
    }

    /** Rules 7 and 8: the lease of the PaymentAttempt as seen by this processing at {@code now}. */
    private static Lease leaseOf(Processing processing, OrderRecord record, Instant now) {
        if (record.order().paymentAttempt() == null) {
            return Lease.NONE;
        }
        PaymentLease lease = record.paymentLease();
        if (lease == null || !lease.inForceAt(now)) {
            return Lease.EXPIRED;
        }
        return lease.owner().equals(processing.leaseOwner()) ? Lease.OWNED : Lease.OTHER_ACTIVE;
    }

    /**
     * Rule 15 and ADR-025: after a cancellation, re-read the Order; a Ticket condition with the Order
     * guard satisfied leads to quarantine, anything else is evaluated again from rule 3.
     */
    private Mono<MessageDisposition> resolveCancellation(Processing processing, Payment payment,
            TransactionOutcome.Cancelled cancelled, int evaluations) {
        return orderReader.findById(processing.orderId())
                .flatMap(reread -> decide(processing, reread, payment, evaluations + 1,
                        OrderQuarantine.applies(cancelled, reread)))
                .switchIfEmpty(Mono.fromSupplier(() -> MessageDisposition.poison(DispositionReason.ENTITY_NOT_FOUND)));
    }

    private Mono<MessageDisposition> quarantineAndDelete(Processing processing, OrderRecord record, Payment payment,
            int evaluations) {
        return quarantine.apply(record.order(), payment.quarantineReason(), actor, processing.correlationId())
                .flatMap(outcome -> switch (outcome) {
                    case TransactionOutcome.Applied applied ->
                            Mono.just(MessageDisposition.delete(DispositionReason.QUARANTINE_APPLIED));
                    case TransactionOutcome.Cancelled cancelled -> read(processing, payment, evaluations + 1);
                    case TransactionOutcome.Conflict conflict -> Mono.error(new TransientFailure("quarantine conflict"));
                });
    }

    // ------------------------------------------------------------------ rule 5 (cutoff) and rule 6 (start payment)

    private Mono<MessageDisposition> expire(Processing processing, OrderRecord record, Payment payment, Instant now,
            int evaluations) {
        Order expired = record.order().expire(now);
        ClosurePlan plan = new ClosurePlan(ClosurePlan.Kind.EXPIRE, record.order(), expired, null,
                WorkerAudits.reservationExpired(expired, false, actor, processing.correlationId(), now), now);
        return close(processing, plan, payment.withQuarantineReason(OrderQuarantine.ON_EXPIRATION),
                MessageDisposition.delete(DispositionReason.EXPIRED), evaluations);
    }

    private Mono<MessageDisposition> startPayment(Processing processing, OrderRecord record, Payment payment,
            Instant now, int evaluations) {
        Order started = record.order().startPayment(now, settings.orderRules().paymentCutoff());
        PaymentLease lease = new PaymentLease(processing.leaseOwner(), now.plus(settings.paymentLeaseDuration()));
        PaymentStartPlan plan = new PaymentStartPlan(record.order(), started, lease,
                WorkerAudits.paymentStarted(started, actor, processing.correlationId(), now), now,
                now.plus(settings.orderRules().paymentCutoff()));
        return lifecycleStore.startPayment(plan).flatMap(outcome -> switch (outcome) {
            case TransactionOutcome.Applied applied -> decide(processing,
                    new OrderRecord(started, record.createdAt(), now, record.enqueuedAt(), lease),
                    payment, evaluations + 1, false);
            case TransactionOutcome.Cancelled cancelled -> resolveCancellation(processing,
                    payment.withQuarantineReason(OrderQuarantine.ON_PAYMENT_START), cancelled, evaluations);
            case TransactionOutcome.Conflict conflict -> Mono.error(new TransientFailure("start payment conflict"));
        });
    }

    // ------------------------------------------------------------------ rules 7..9 (lease and authorization)

    private Mono<MessageDisposition> claimLease(Processing processing, OrderRecord record, Payment payment,
            Instant now, int evaluations) {
        PaymentLease lease = new PaymentLease(processing.leaseOwner(), now.plus(settings.paymentLeaseDuration()));
        String attemptId = record.order().paymentAttempt().paymentAttemptId();
        return lifecycleStore.claimPaymentLease(processing.orderId(), attemptId, lease, now)
                .defaultIfEmpty(Boolean.FALSE)
                .flatMap(claimed -> claimed
                        ? decide(processing, new OrderRecord(record.order(), record.createdAt(), now,
                                record.enqueuedAt(), lease), payment, evaluations + 1, false)
                        : read(processing, payment, evaluations + 1));
    }

    /**
     * Rule 9: authorization with {@code paymentAttemptId} as idempotency key; the wait is bounded by
     * {@code expiresAt} minus the application margin (ADR-008 point 4). A deadline already reached is a
     * transient outcome without calling the provider.
     */
    private Mono<MessageDisposition> authorize(Processing processing, OrderRecord record, Instant now, int evaluations) {
        Order order = record.order();
        Instant deadline = order.reservation().expiresAt().minus(settings.authorizationMargin());
        if (!deadline.isAfter(now)) {
            return decide(processing, record, Payment.TRANSIENT, evaluations + 1, false);
        }
        PaymentAuthorization request = new PaymentAuthorization(order.paymentAttempt().paymentAttemptId(),
                order.orderId(), order.eventId(), order.customerId(), order.ticketIds());
        return paymentGateway.authorize(request, deadline)
                .defaultIfEmpty(new AuthorizationOutcome.DependencyUnavailable("no result"))
                .onErrorResume(error -> Mono.just(new AuthorizationOutcome.DependencyUnavailable("gateway error")))
                .flatMap(outcome -> decide(processing, record, Payment.of(outcome), evaluations + 1, false));
    }

    // ------------------------------------------------------------------ rules 10..12 (provider result)

    private Mono<MessageDisposition> confirm(Processing processing, OrderRecord record, Payment payment, Instant now,
            int evaluations) {
        Order order = record.order();
        if (!order.reservation().expiresAt().isAfter(now)) {
            return expireAfterLateApproval(processing, record, payment, now, evaluations);
        }
        Order confirmed = order.recordPaymentOutcome(PaymentOutcome.APPROVED).confirm(now);
        ConfirmationPlan plan = new ConfirmationPlan(order, confirmed, payment.providerReference(),
                WorkerAudits.paymentApproved(confirmed, actor, processing.correlationId(), now), now);
        return lifecycleStore.confirm(plan).flatMap(outcome -> switch (outcome) {
            case TransactionOutcome.Applied applied -> Mono.just(MessageDisposition.delete(DispositionReason.CONFIRMED));
            case TransactionOutcome.Cancelled cancelled -> resolveApproval(processing,
                    payment.withQuarantineReason(OrderQuarantine.ON_CONFIRMATION), cancelled, evaluations);
            case TransactionOutcome.Conflict conflict -> Mono.error(new TransientFailure("confirm conflict"));
        });
    }

    /**
     * Rule 10 after a cancelled confirmation (ADR-008 point 5): still {@code CREATED} and expired, expire
     * it with the reversal mark and the late approval in its audit; already closed without confirmation,
     * record the late approval (AP-032); otherwise evaluate again (rule 15 included).
     */
    private Mono<MessageDisposition> resolveApproval(Processing processing, Payment payment,
            TransactionOutcome.Cancelled cancelled, int evaluations) {
        return orderReader.findById(processing.orderId())
                .flatMap(reread -> {
                    Order order = reread.order();
                    Instant now = clock.now();
                    if (order.status() == OrderStatus.CREATED && order.quarantinedAt() == null
                            && !order.reservation().expiresAt().isAfter(now)) {
                        return expireAfterLateApproval(processing, reread, payment, now, evaluations + 1);
                    }
                    if (closedWithoutConfirmation(order)) {
                        return recordLateApproval(processing, reread, now);
                    }
                    return decide(processing, reread, payment, evaluations + 1, OrderQuarantine.applies(cancelled, reread));
                })
                .switchIfEmpty(Mono.fromSupplier(() -> MessageDisposition.poison(DispositionReason.ENTITY_NOT_FOUND)));
    }

    private Mono<MessageDisposition> expireAfterLateApproval(Processing processing, OrderRecord record,
            Payment payment, Instant now, int evaluations) {
        if (evaluations > settings.maximumReevaluations()) {
            return Mono.error(new TransientFailure("too many re-evaluations"));
        }
        Order expired = record.order().expire(now);
        ClosurePlan plan = new ClosurePlan(ClosurePlan.Kind.EXPIRE, record.order(), expired, null,
                WorkerAudits.reservationExpired(expired, true, actor, processing.correlationId(), now), now);
        return lifecycleStore.close(plan).flatMap(outcome -> switch (outcome) {
            case TransactionOutcome.Applied applied -> Mono.just(MessageDisposition.delete(DispositionReason.EXPIRED));
            case TransactionOutcome.Cancelled cancelled -> resolveApproval(processing,
                    payment.withQuarantineReason(OrderQuarantine.ON_EXPIRATION), cancelled, evaluations);
            case TransactionOutcome.Conflict conflict -> Mono.error(new TransientFailure("expire conflict"));
        });
    }

    /**
     * AP-032 on an Order closed without confirmation. Its guard can only fail once the late approval is
     * already recorded, so a cancellation leaves the terminal Order as it is (rule 3).
     */
    private Mono<MessageDisposition> recordLateApproval(Processing processing, OrderRecord record, Instant now) {
        Order current = record.order();
        Order updated = current.recordLateApproval(now);
        LateApprovalPlan plan = new LateApprovalPlan(current, updated, WorkerAudits.lateApprovalNotApplied(
                current, current.lateApprovalMarksReversal(), actor, processing.correlationId(), now));
        return lifecycleStore.recordLateApproval(plan).flatMap(outcome -> switch (outcome) {
            case TransactionOutcome.Applied applied ->
                    Mono.just(MessageDisposition.delete(DispositionReason.LATE_APPROVAL_RECORDED));
            case TransactionOutcome.Cancelled cancelled ->
                    Mono.just(MessageDisposition.delete(DispositionReason.ALREADY_TERMINAL));
            case TransactionOutcome.Conflict conflict -> Mono.error(new TransientFailure("late approval conflict"));
        });
    }

    private static boolean closedWithoutConfirmation(Order order) {
        return order.status().terminal() && order.status() != OrderStatus.CONFIRMED && order.paymentAttempt() != null;
    }

    private Mono<MessageDisposition> reject(Processing processing, OrderRecord record, Payment payment, Instant now,
            int evaluations) {
        Order rejected = record.order().recordPaymentOutcome(PaymentOutcome.DECLINED).reject();
        ClosurePlan plan = new ClosurePlan(ClosurePlan.Kind.REJECT, record.order(), rejected,
                payment.providerReference(),
                WorkerAudits.paymentDeclined(rejected, actor, processing.correlationId(), now), now);
        return close(processing, plan, payment.withQuarantineReason(OrderQuarantine.ON_REJECTION),
                MessageDisposition.delete(DispositionReason.REJECTED), evaluations);
    }

    private Mono<MessageDisposition> failDefinitively(Processing processing, OrderRecord record, Payment payment,
            Instant now, int evaluations) {
        Order failed = record.order().recordPaymentOutcome(PaymentOutcome.DEFINITIVE_ERROR).failProcessing(now);
        ClosurePlan plan = new ClosurePlan(ClosurePlan.Kind.FAIL_PROCESSING, record.order(), failed, null,
                WorkerAudits.processingFailed(failed, actor, processing.correlationId(), now), now);
        return close(processing, plan, payment.withQuarantineReason(OrderQuarantine.ON_PROCESSING_FAILURE),
                MessageDisposition.delete(DispositionReason.FAILED_DEFINITIVE), evaluations);
    }

    private Mono<MessageDisposition> close(Processing processing, ClosurePlan plan, Payment payment,
            MessageDisposition onApplied, int evaluations) {
        return lifecycleStore.close(plan).flatMap(outcome -> switch (outcome) {
            case TransactionOutcome.Applied applied -> Mono.just(onApplied);
            case TransactionOutcome.Cancelled cancelled -> resolveCancellation(processing, payment, cancelled, evaluations);
            case TransactionOutcome.Conflict conflict -> Mono.error(new TransientFailure("close conflict"));
        });
    }

    // ------------------------------------------------------------------ rules 13 and 14 (transient failures)

    private Mono<MessageDisposition> transientFailure(Processing processing) {
        if (!processing.lastReception()) {
            return Mono.just(MessageDisposition.retry(DispositionReason.TRANSIENT_FAILURE));
        }
        return orderReader.findById(processing.orderId())
                .flatMap(record -> {
                    Order order = record.order();
                    if (order.status().terminal()) {
                        return Mono.just(MessageDisposition.delete(DispositionReason.ALREADY_TERMINAL));
                    }
                    if (order.quarantinedAt() != null) {
                        return Mono.just(MessageDisposition.delete(DispositionReason.QUARANTINED));
                    }
                    if (leaseOf(processing, record, clock.now()) == Lease.OTHER_ACTIVE) {
                        return Mono.just(MessageDisposition.postponeUntil(
                                record.paymentLease().until(), DispositionReason.LEASE_HELD_ELSEWHERE));
                    }
                    return failExhausted(processing, record);
                })
                .switchIfEmpty(Mono.fromSupplier(() -> MessageDisposition.poison(DispositionReason.ENTITY_NOT_FOUND)))
                .onErrorResume(error -> Mono.just(MessageDisposition.retry(DispositionReason.TRANSIENT_FAILURE)));
    }

    /**
     * Rule 14: transient failure on the last reception. The Order is closed as {@code FAILED}
     * ({@code PROCESSING_FAILED}) with the reversal mark when it has a PaymentAttempt of unknown outcome;
     * the message is not deleted and moves to the DLQ.
     */
    private Mono<MessageDisposition> failExhausted(Processing processing, OrderRecord record) {
        Instant now = clock.now();
        Order failed = record.order().failProcessing(now);
        ClosurePlan plan = new ClosurePlan(ClosurePlan.Kind.FAIL_PROCESSING, record.order(), failed, null,
                WorkerAudits.processingFailed(failed, actor, processing.correlationId(), now), now);
        return lifecycleStore.close(plan).flatMap(outcome -> switch (outcome) {
            case TransactionOutcome.Applied applied -> Mono.just(MessageDisposition.retry(DispositionReason.EXHAUSTED));
            case TransactionOutcome.Cancelled cancelled -> afterCancelledExhaustion(processing, cancelled);
            case TransactionOutcome.Conflict conflict ->
                    Mono.just(MessageDisposition.retry(DispositionReason.TRANSIENT_FAILURE));
        });
    }

    private Mono<MessageDisposition> afterCancelledExhaustion(Processing processing,
            TransactionOutcome.Cancelled cancelled) {
        return orderReader.findById(processing.orderId())
                .flatMap(reread -> {
                    if (OrderQuarantine.applies(cancelled, reread)) {
                        return quarantine.apply(reread.order(), OrderQuarantine.ON_PROCESSING_FAILURE, actor,
                                        processing.correlationId())
                                .map(outcome -> outcome instanceof TransactionOutcome.Applied
                                        ? MessageDisposition.delete(DispositionReason.QUARANTINE_APPLIED)
                                        : MessageDisposition.retry(DispositionReason.TRANSIENT_FAILURE));
                    }
                    if (reread.order().status().terminal()) {
                        return Mono.just(MessageDisposition.delete(DispositionReason.ALREADY_TERMINAL));
                    }
                    if (reread.order().quarantinedAt() != null) {
                        return Mono.just(MessageDisposition.delete(DispositionReason.QUARANTINED));
                    }
                    return Mono.just(MessageDisposition.retry(DispositionReason.TRANSIENT_FAILURE));
                })
                .switchIfEmpty(Mono.fromSupplier(() -> MessageDisposition.poison(DispositionReason.ENTITY_NOT_FOUND)));
    }

    // ------------------------------------------------------------------ processing state

    /** One processing of one delivery; {@code leaseOwner} is unique per processing and worker instance. */
    private record Processing(String orderId, String correlationId, boolean lastReception, String leaseOwner) {
    }

    /**
     * Provider result obtained in this processing (kept across re-evaluations so that a re-read never
     * calls the provider twice) and the quarantine reason of the transition being resolved.
     */
    private record Payment(ProviderResult result, String providerReference, String quarantineReason) {

        static final Payment NONE = new Payment(ProviderResult.NONE, null, OrderQuarantine.ON_PAYMENT_START);
        static final Payment TRANSIENT = new Payment(ProviderResult.TRANSIENT, null, OrderQuarantine.ON_PAYMENT_START);

        static Payment of(AuthorizationOutcome outcome) {
            return switch (outcome) {
                case AuthorizationOutcome.Approved approved ->
                        new Payment(ProviderResult.APPROVED, approved.providerReference(), OrderQuarantine.ON_CONFIRMATION);
                case AuthorizationOutcome.Declined declined ->
                        new Payment(ProviderResult.DECLINED, declined.providerReference(), OrderQuarantine.ON_REJECTION);
                case AuthorizationOutcome.ContractError error ->
                        new Payment(ProviderResult.DEFINITIVE_ERROR, null, OrderQuarantine.ON_PROCESSING_FAILURE);
                case AuthorizationOutcome.DependencyUnavailable unavailable -> TRANSIENT;
            };
        }

        Payment withQuarantineReason(String reason) {
            return new Payment(result, providerReference, reason);
        }
    }

    /** Transient failure signalled inside the processing chain (conflict, bounded re-evaluation). */
    private static final class TransientFailure extends RuntimeException {
        TransientFailure(String message) {
            super(message, null, false, false);
        }
    }
}
