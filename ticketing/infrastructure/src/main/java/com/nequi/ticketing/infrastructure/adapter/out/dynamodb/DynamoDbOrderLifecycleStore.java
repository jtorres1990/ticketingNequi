package com.nequi.ticketing.infrastructure.adapter.out.dynamodb;

import static com.nequi.ticketing.infrastructure.adapter.out.dynamodb.Attributes.bool;
import static com.nequi.ticketing.infrastructure.adapter.out.dynamodb.Attributes.instant;
import static com.nequi.ticketing.infrastructure.adapter.out.dynamodb.Attributes.millis;
import static com.nequi.ticketing.infrastructure.adapter.out.dynamodb.Attributes.n;
import static com.nequi.ticketing.infrastructure.adapter.out.dynamodb.Attributes.s;

import com.nequi.ticketing.application.port.out.ClosurePlan;
import com.nequi.ticketing.application.port.out.ConfirmationPlan;
import com.nequi.ticketing.application.port.out.EnqueueFailurePlan;
import com.nequi.ticketing.application.port.out.FailedItem;
import com.nequi.ticketing.application.port.out.LateApprovalPlan;
import com.nequi.ticketing.application.port.out.OrderLifecycleStore;
import com.nequi.ticketing.application.port.out.PaymentLease;
import com.nequi.ticketing.application.port.out.PaymentStartPlan;
import com.nequi.ticketing.application.port.out.QuarantinePlan;
import com.nequi.ticketing.application.port.out.ReservationPlan;
import com.nequi.ticketing.application.port.out.ReversalCompletionPlan;
import com.nequi.ticketing.application.port.out.ReversalExhaustionPlan;
import com.nequi.ticketing.application.port.out.TransactionOutcome;
import com.nequi.ticketing.domain.audit.AuditRecord;
import com.nequi.ticketing.domain.event.Event;
import com.nequi.ticketing.domain.order.ActiveOrderKey;
import com.nequi.ticketing.domain.order.Order;
import com.nequi.ticketing.domain.order.Order.OrderStatus;
import com.nequi.ticketing.domain.order.Order.PaymentAttempt;
import com.nequi.ticketing.domain.order.Order.PaymentOutcome;
import com.nequi.ticketing.domain.order.ReversalPlan;
import com.nequi.ticketing.domain.ticket.Ticket.TicketState;
import com.nequi.ticketing.infrastructure.adapter.out.dynamodb.DynamoDbTable.ItemRole;
import com.nequi.ticketing.infrastructure.adapter.out.dynamodb.DynamoDbTable.TxItem;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * CMP-010 {@code OrderLifecycleStore} over DynamoDB: every Order transition of ADR-025 with exactly the
 * items and conditions of {@code ticketing.data-model.v2.md} §5 and the transaction sizes of the
 * consolidation addendum (reserve N + 4 = 14, terminal N + 3 = 13, start payment N + 2 = 12). Releasing a
 * Ticket re-enters {@code GSI2} under the shard of the Event, read from the immutable {@code ENABLED} Event
 * (AP-006, cached). A failed condition is a typed result, never an error (ADR-025 rule 5).
 */
public final class DynamoDbOrderLifecycleStore implements OrderLifecycleStore {

    private static final String LOCK_ENTITY = "ACTIVE_ORDER_LOCK";
    private static final String LOCK_ORDER_ID = "orderId";

    private final DynamoDbTable table;
    private final DynamoDbEventCatalog events;

    DynamoDbOrderLifecycleStore(DynamoDbTable table, DynamoDbEventCatalog events) {
        this.table = Objects.requireNonNull(table, "table");
        this.events = Objects.requireNonNull(events, "events");
    }

    // ------------------------------------------------------------------ api role

    /** AP-008: N Tickets reserved, Order, idempotency record, audit and active Order lock (N + 4 items). */
    @Override
    public Mono<TransactionOutcome> reserve(ReservationPlan plan) {
        return Mono.defer(() -> {
            Order order = plan.order();
            Instant now = order.reservation().reservedAt();
            List<TxItem> items = new ArrayList<>();
            for (String ticketId : order.ticketIds()) {
                Expression update = new Expression()
                        .set(TicketItems.STATE, s(TicketState.RESERVED.name()))
                        .set(TicketItems.ORDER_ID, s(order.orderId()))
                        .set(TicketItems.UPDATED_AT, instant(now))
                        .remove(TicketItems.GSI2PK, TicketItems.GSI2SK);
                update.condition(update.exists(Keys.PK))
                        .condition(update.eq(TicketItems.EVENT_ID, s(order.eventId())))
                        .condition(update.eq(TicketItems.STATE, s(TicketState.AVAILABLE.name())));
                items.add(table.update(TicketItems.key(order.eventId(), ticketId), update,
                        ItemRole.ticket(order.eventId(), ticketId)));
            }
            items.add(table.insert(OrderItems.newOrder(order), ItemRole.of(FailedItem.ORDER)));
            items.add(table.insert(IdempotencyItems.purchase(plan.idempotency()), ItemRole.of(FailedItem.IDEMPOTENCY_RECORD)));
            items.add(table.insert(AuditItems.item(plan.audit()), ItemRole.of(FailedItem.AUDIT)));
            items.add(table.insert(lock(plan.activeOrderKey(), order.orderId(), now), ItemRole.of(FailedItem.ACTIVE_ORDER_LOCK)));
            return table.transact("AP-008 reserve", items);
        });
    }

    /** AP-011: {@code enqueuedAt}, out of {@code GSI4}; {@code CREATED} without {@code enqueuedAt}. Keeps the lease. */
    @Override
    public Mono<Boolean> markEnqueued(String orderId, Instant enqueuedAt) {
        return Mono.defer(() -> {
            Expression update = new Expression()
                    .set(OrderItems.ENQUEUED_AT, instant(enqueuedAt))
                    .remove(OrderItems.GSI4PK, OrderItems.GSI4SK);
            update.condition(update.eq(OrderItems.STATUS, s(OrderStatus.CREATED.name())))
                    .condition(update.notExists(OrderItems.ENQUEUED_AT));
            return table.conditionalUpdate("AP-011 mark enqueued", Keys.meta(Keys.order(orderId)), update);
        });
    }

    /** AP-015 "Fail (enqueue)": {@code CREATED} without PaymentAttempt; Tickets released; lock removed (N + 3). */
    @Override
    public Mono<TransactionOutcome> failEnqueue(EnqueueFailurePlan plan) {
        return Mono.defer(() -> {
            Order current = plan.current();
            Expression update = terminal(plan.failed(), plan.failedAt(), null);
            update.condition(update.eq(OrderItems.STATUS, s(OrderStatus.CREATED.name())))
                    .condition(update.notExists(OrderItems.PAYMENT_ATTEMPT_ID));
            return release("AP-015 fail (enqueue)", current, update, plan.audit(), plan.failedAt());
        });
    }

    /** AP-031: quarantine attributes, out of {@code RESV#} and {@code GSI4}, into {@code REVIEW#QUARANTINE} (2 items). */
    @Override
    public Mono<TransactionOutcome> quarantine(QuarantinePlan plan) {
        return Mono.defer(() -> {
            Order quarantined = plan.quarantined();
            Expression update = new Expression()
                    .set(OrderItems.QUARANTINED_AT, instant(quarantined.quarantinedAt()))
                    .set(OrderItems.QUARANTINE_REASON, s(quarantined.quarantineReason()))
                    .set(OrderItems.UPDATED_AT, instant(quarantined.quarantinedAt()))
                    .set(OrderItems.GSI3PK, s(Keys.REVIEW_QUARANTINE))
                    .set(OrderItems.GSI3SK, s(Keys.instantSort(quarantined.quarantinedAt(), quarantined.orderId())))
                    .remove(OrderItems.GSI4PK, OrderItems.GSI4SK);
            update.condition(update.eq(OrderItems.STATUS, s(OrderStatus.CREATED.name())))
                    .condition(update.notExists(OrderItems.QUARANTINED_AT));
            return table.transact("AP-031 quarantine", List.of(
                    table.update(Keys.meta(Keys.order(quarantined.orderId())), update, ItemRole.of(FailedItem.ORDER)),
                    table.insert(AuditItems.item(plan.audit()), ItemRole.of(FailedItem.AUDIT))));
        });
    }

    // ------------------------------------------------------------------ worker role

    /** AP-012: PaymentAttempt and lease; Tickets {@code RESERVED -> PENDING_CONFIRMATION}; audit (N + 2). */
    @Override
    public Mono<TransactionOutcome> startPayment(PaymentStartPlan plan) {
        return Mono.defer(() -> {
            Order current = plan.current();
            PaymentAttempt attempt = plan.started().paymentAttempt();
            Expression update = new Expression()
                    .set(OrderItems.PAYMENT_ATTEMPT_ID, s(attempt.paymentAttemptId()))
                    .set(OrderItems.PAYMENT_ATTEMPT_NO, n(1))
                    .set(OrderItems.PAYMENT_STARTED_AT, instant(attempt.startedAt()))
                    .set(OrderItems.PAYMENT_OUTCOME, s(attempt.outcome().name()))
                    .set(OrderItems.PAYMENT_LEASE_OWNER, s(plan.lease().owner()))
                    .set(OrderItems.PAYMENT_LEASE_UNTIL_MS, millis(plan.lease().until()))
                    .set(OrderItems.UPDATED_AT, instant(plan.now()));
            update.condition(update.eq(OrderItems.STATUS, s(OrderStatus.CREATED.name())))
                    .condition(update.notExists(OrderItems.PAYMENT_ATTEMPT_ID))
                    .condition(update.notExists(OrderItems.QUARANTINED_AT))
                    .condition(update.compare(OrderItems.EXPIRES_AT_MS, ">", millis(plan.cutoffInstant())));
            List<TxItem> items = new ArrayList<>();
            items.add(table.update(Keys.meta(Keys.order(current.orderId())), update, ItemRole.of(FailedItem.ORDER)));
            for (String ticketId : current.ticketIds()) {
                Expression ticket = new Expression()
                        .set(TicketItems.STATE, s(TicketState.PENDING_CONFIRMATION.name()))
                        .set(TicketItems.UPDATED_AT, instant(plan.now()));
                ticket.condition(ticket.eq(TicketItems.STATE, s(TicketState.RESERVED.name())))
                        .condition(ticket.eq(TicketItems.ORDER_ID, s(current.orderId())));
                items.add(table.update(TicketItems.key(current.eventId(), ticketId), ticket,
                        ItemRole.ticket(current.eventId(), ticketId)));
            }
            items.add(table.insert(AuditItems.item(plan.audit()), ItemRole.of(FailedItem.AUDIT)));
            return table.transact("AP-012 start payment", items);
        });
    }

    /** AP-013: new lease; {@code CREATED}, same PaymentAttempt, expired lease, no quarantine. */
    @Override
    public Mono<Boolean> claimPaymentLease(String orderId, String paymentAttemptId, PaymentLease lease, Instant now) {
        return Mono.defer(() -> {
            Expression update = new Expression()
                    .set(OrderItems.PAYMENT_LEASE_OWNER, s(lease.owner()))
                    .set(OrderItems.PAYMENT_LEASE_UNTIL_MS, millis(lease.until()))
                    .set(OrderItems.UPDATED_AT, instant(now));
            update.condition(update.eq(OrderItems.STATUS, s(OrderStatus.CREATED.name())))
                    .condition(update.eq(OrderItems.PAYMENT_ATTEMPT_ID, s(paymentAttemptId)))
                    .condition(update.compare(OrderItems.PAYMENT_LEASE_UNTIL_MS, "<", millis(now)))
                    .condition(update.notExists(OrderItems.QUARANTINED_AT));
            return table.conditionalUpdate("AP-013 claim lease", Keys.meta(Keys.order(orderId)), update);
        });
    }

    /** AP-014: Order {@code CONFIRMED}, Tickets {@code SOLD}, audit, lock removed (N + 3). */
    @Override
    public Mono<TransactionOutcome> confirm(ConfirmationPlan plan) {
        return Mono.defer(() -> {
            Order current = plan.current();
            Expression update = terminal(plan.confirmed(), plan.now(), plan.providerReference());
            update.condition(update.eq(OrderItems.STATUS, s(OrderStatus.CREATED.name())))
                    .condition(update.notExists(OrderItems.QUARANTINED_AT))
                    .condition(update.eq(OrderItems.PAYMENT_ATTEMPT_ID, s(current.paymentAttempt().paymentAttemptId())))
                    .condition(update.compare(OrderItems.EXPIRES_AT_MS, ">", millis(plan.now())));
            return terminalTransaction("AP-014 confirm", current, update, plan.audit(), ticketId -> {
                Expression ticket = new Expression()
                        .set(TicketItems.STATE, s(TicketState.SOLD.name()))
                        .set(TicketItems.UPDATED_AT, instant(plan.now()));
                ticket.condition(ticket.eq(TicketItems.STATE, s(TicketState.PENDING_CONFIRMATION.name())))
                        .condition(ticket.eq(TicketItems.ORDER_ID, s(current.orderId())));
                return ticket;
            });
        });
    }

    /** AP-015 "Reject", "Fail (processing)" and "Expire" with the guards of each kind (N + 3). */
    @Override
    public Mono<TransactionOutcome> close(ClosurePlan plan) {
        return Mono.defer(() -> {
            Order current = plan.current();
            Expression update = terminal(plan.closed(), plan.now(), plan.providerReference());
            update.condition(update.eq(OrderItems.STATUS, s(OrderStatus.CREATED.name())))
                    .condition(update.notExists(OrderItems.QUARANTINED_AT));
            switch (plan.kind()) {
                case REJECT -> update.condition(
                        update.eq(OrderItems.PAYMENT_ATTEMPT_ID, s(current.paymentAttempt().paymentAttemptId())));
                case FAIL_PROCESSING -> update.condition(current.paymentAttempt() == null
                        ? update.notExists(OrderItems.PAYMENT_ATTEMPT_ID)
                        : update.eq(OrderItems.PAYMENT_ATTEMPT_ID, s(current.paymentAttempt().paymentAttemptId())));
                case EXPIRE -> update.condition(update.compare(OrderItems.EXPIRES_AT_MS, "<=", millis(plan.now())));
            }
            return release("AP-015 " + plan.kind(), current, update, plan.audit(), plan.now());
        });
    }

    /**
     * AP-032: audit {@code LATE_APPROVAL_NOT_APPLIED} plus the reversal mark when the Order read had none;
     * otherwise only the audit, with the Order guard as a condition check (2 items).
     */
    @Override
    public Mono<TransactionOutcome> recordLateApproval(LateApprovalPlan plan) {
        return Mono.defer(() -> {
            Order current = plan.current();
            Map<String, AttributeValue> key = Keys.meta(Keys.order(current.orderId()));
            Expression guard = new Expression();
            TxItem order;
            if (current.reversalPlan() == null) {
                ReversalPlan mark = plan.updated().reversalPlan();
                guard.set(OrderItems.UPDATED_AT, instant(plan.audit().occurredAt()));
                markReversal(guard, current.orderId(), mark);
                lateApprovalGuard(guard, current);
                guard.condition(guard.notExists(OrderItems.REVERSAL_REQUESTED_AT));
                order = table.update(key, guard, ItemRole.of(FailedItem.ORDER));
            } else {
                lateApprovalGuard(guard, current);
                order = table.conditionCheck(key, guard, ItemRole.of(FailedItem.ORDER));
            }
            return table.transact("AP-032 late approval", List.of(
                    order, table.insert(AuditItems.item(plan.audit()), ItemRole.of(FailedItem.AUDIT))));
        });
    }

    /** AP-030 complete: mark and {@code GSI3} removed, {@code paymentReversalCompletedAt}; audit (2 items). */
    @Override
    public Mono<TransactionOutcome> completeReversal(ReversalCompletionPlan plan) {
        return Mono.defer(() -> {
            Order completed = plan.completed();
            Expression update = new Expression()
                    .set(OrderItems.REVERSAL_COMPLETED_AT, instant(completed.reversalPlan().completedAt()))
                    .set(OrderItems.UPDATED_AT, instant(plan.audit().occurredAt()))
                    .remove(OrderItems.REVERSAL_PENDING, OrderItems.GSI3PK, OrderItems.GSI3SK);
            update.condition(update.eq(OrderItems.REVERSAL_PENDING, bool(true)))
                    .condition(update.eq(OrderItems.PAYMENT_ATTEMPT_ID,
                            s(plan.current().reversalPlan().paymentAttemptId())));
            return table.transact("AP-030 complete reversal", List.of(
                    table.update(Keys.meta(Keys.order(completed.orderId())), update, ItemRole.of(FailedItem.ORDER)),
                    table.insert(AuditItems.item(plan.audit()), ItemRole.of(FailedItem.AUDIT))));
        });
    }

    /** AP-030 exhaust: {@code REVERSAL#EXHAUSTED} with {@code paymentReversalExhaustedAt}; audit (2 items). */
    @Override
    public Mono<TransactionOutcome> exhaustReversal(ReversalExhaustionPlan plan) {
        return Mono.defer(() -> {
            Order exhausted = plan.exhausted();
            ReversalPlan reversal = exhausted.reversalPlan();
            Instant exhaustedAt = reversal.nextAttemptAt();
            Expression update = new Expression()
                    .set(OrderItems.REVERSAL_ATTEMPTS, n(reversal.attempts()))
                    .set(OrderItems.REVERSAL_NEXT_ATTEMPT_AT_MS, millis(exhaustedAt))
                    .set(OrderItems.REVERSAL_EXHAUSTED_AT, instant(exhaustedAt))
                    .set(OrderItems.UPDATED_AT, instant(plan.audit().occurredAt()))
                    .set(OrderItems.GSI3PK, s(Keys.REVERSAL_EXHAUSTED))
                    .set(OrderItems.GSI3SK, s(Keys.instantSort(exhaustedAt, exhausted.orderId())));
            update.condition(update.eq(OrderItems.REVERSAL_PENDING, bool(true)));
            return table.transact("AP-030 exhaust reversal", List.of(
                    table.update(Keys.meta(Keys.order(exhausted.orderId())), update, ItemRole.of(FailedItem.ORDER)),
                    table.insert(AuditItems.item(plan.audit()), ItemRole.of(FailedItem.AUDIT))));
        });
    }

    /** AP-030 reschedule: attempts and next attempt ({@code GSI3SK}); pending with the attempts read. */
    @Override
    public Mono<Boolean> rescheduleReversal(String orderId, int expectedAttempts, ReversalPlan next) {
        return Mono.defer(() -> {
            Expression update = new Expression()
                    .set(OrderItems.REVERSAL_ATTEMPTS, n(next.attempts()))
                    .set(OrderItems.REVERSAL_NEXT_ATTEMPT_AT_MS, millis(next.nextAttemptAt()))
                    .set(OrderItems.GSI3SK, s(Keys.millisSort(next.nextAttemptAt(), orderId)));
            update.condition(update.eq(OrderItems.REVERSAL_PENDING, bool(true)))
                    .condition(update.eq(OrderItems.REVERSAL_ATTEMPTS, n(expectedAttempts)));
            return table.conditionalUpdate("AP-030 reschedule reversal", Keys.meta(Keys.order(orderId)), update);
        });
    }

    // ------------------------------------------------------------------ shared pieces

    /**
     * Terminal state of the Order item: status, cause, payment outcome, provider reference, reversal mark
     * (with its {@code REVERSAL#} entry) and removal of the active Order indexes and of the lease.
     */
    private static Expression terminal(Order target, Instant now, String providerReference) {
        Expression update = new Expression()
                .set(OrderItems.STATUS, s(target.status().name()))
                .set(OrderItems.UPDATED_AT, instant(now))
                .set(OrderItems.TERMINAL_AT, instant(now))
                .setIfPresent(OrderItems.FAILURE_CAUSE, target.failureCause() == null ? null : s(target.failureCause().name()))
                .setIfPresent(OrderItems.PAYMENT_PROVIDER_REF, providerReference == null ? null : s(providerReference));
        PaymentAttempt attempt = target.paymentAttempt();
        if (attempt != null) {
            update.set(OrderItems.PAYMENT_OUTCOME, s(attempt.outcome().name()));
            if (attempt.outcome() != PaymentOutcome.UNKNOWN) {
                update.set(OrderItems.PAYMENT_COMPLETED_AT, instant(now));
            }
        }
        if (target.reversalPlan() != null) {
            markReversal(update, target.orderId(), target.reversalPlan());
        } else {
            update.remove(OrderItems.GSI3PK, OrderItems.GSI3SK);
        }
        return update.remove(OrderItems.GSI4PK, OrderItems.GSI4SK,
                OrderItems.PAYMENT_LEASE_OWNER, OrderItems.PAYMENT_LEASE_UNTIL_MS);
    }

    private static void markReversal(Expression update, String orderId, ReversalPlan reversal) {
        update.set(OrderItems.REVERSAL_PENDING, bool(true))
                .set(OrderItems.REVERSAL_REQUESTED_AT, instant(reversal.requestedAt()))
                .set(OrderItems.REVERSAL_ATTEMPTS, n(reversal.attempts()))
                .set(OrderItems.REVERSAL_NEXT_ATTEMPT_AT_MS, millis(reversal.nextAttemptAt()))
                .set(OrderItems.GSI3PK, s(Keys.reversals(orderId)))
                .set(OrderItems.GSI3SK, s(Keys.millisSort(reversal.nextAttemptAt(), orderId)));
    }

    private static void lateApprovalGuard(Expression guard, Order current) {
        guard.condition(guard.in(OrderItems.STATUS, s(OrderStatus.EXPIRED.name()), s(OrderStatus.FAILED.name()),
                        s(OrderStatus.REJECTED.name())))
                .condition(guard.eq(OrderItems.PAYMENT_ATTEMPT_ID, s(current.paymentAttempt().paymentAttemptId())));
    }

    /** Terminal transition that releases the Tickets: each {@code RESERVED} or {@code PENDING_CONFIRMATION} of this Order. */
    private Mono<TransactionOutcome> release(String operation, Order current, Expression orderUpdate, AuditRecord audit,
            Instant now) {
        return events.findEvent(current.eventId())
                .switchIfEmpty(Mono.error(() -> new DynamoDbStoreException(operation + ": Event " + current.eventId()
                        + " of Order " + current.orderId() + " not found")))
                .flatMap(event -> terminalTransaction(operation, current, orderUpdate, audit,
                        ticketId -> releaseTicket(event, current.orderId(), ticketId, now)));
    }

    private static Expression releaseTicket(Event event, String orderId, String ticketId, Instant now) {
        Expression ticket = new Expression()
                .set(TicketItems.STATE, s(TicketState.AVAILABLE.name()))
                .set(TicketItems.UPDATED_AT, instant(now))
                .set(TicketItems.GSI2PK, s(TicketItems.availabilityPartition(event.eventId(), ticketId,
                        event.availabilityShards())))
                .set(TicketItems.GSI2SK, s(TicketItems.availabilitySort(ticketId)))
                .remove(TicketItems.ORDER_ID);
        ticket.condition(ticket.in(TicketItems.STATE, s(TicketState.RESERVED.name()),
                        s(TicketState.PENDING_CONFIRMATION.name())))
                .condition(ticket.eq(TicketItems.ORDER_ID, s(orderId)));
        return ticket;
    }

    /** Order, N Tickets, audit and removal of the lock ("absent or owned by this Order"): N + 3 items. */
    private Mono<TransactionOutcome> terminalTransaction(String operation, Order current, Expression orderUpdate,
            AuditRecord audit, Function<String, Expression> ticketUpdate) {
        List<TxItem> items = new ArrayList<>();
        items.add(table.update(Keys.meta(Keys.order(current.orderId())), orderUpdate, ItemRole.of(FailedItem.ORDER)));
        for (String ticketId : current.ticketIds()) {
            items.add(table.update(TicketItems.key(current.eventId(), ticketId), ticketUpdate.apply(ticketId),
                    ItemRole.ticket(current.eventId(), ticketId)));
        }
        items.add(table.insert(AuditItems.item(audit), ItemRole.of(FailedItem.AUDIT)));
        Expression lockCondition = new Expression();
        lockCondition.condition(Expression.or(lockCondition.notExists(Keys.PK),
                lockCondition.eq(LOCK_ORDER_ID, s(current.orderId()))));
        items.add(table.delete(Keys.meta(Keys.activeOrder(current.customerId(), current.eventId())), lockCondition,
                ItemRole.of(FailedItem.ACTIVE_ORDER_LOCK)));
        return table.transact(operation, items);
    }

    private static Map<String, AttributeValue> lock(ActiveOrderKey key, String orderId, Instant createdAt) {
        Map<String, AttributeValue> item = new HashMap<>(Keys.meta(Keys.activeOrder(key.customerId(), key.eventId())));
        item.put("entityType", s(LOCK_ENTITY));
        item.put(LOCK_ORDER_ID, s(orderId));
        item.put("customerId", s(key.customerId()));
        item.put("eventId", s(key.eventId()));
        item.put("createdAt", instant(createdAt));
        return item;
    }
}
