package com.nequi.ticketing.application.usecase;

import com.nequi.ticketing.application.port.out.Clock;
import com.nequi.ticketing.application.port.out.FailedItem;
import com.nequi.ticketing.application.port.out.OrderLifecycleStore;
import com.nequi.ticketing.application.port.out.OrderRecord;
import com.nequi.ticketing.application.port.out.QuarantinePlan;
import com.nequi.ticketing.application.port.out.TransactionOutcome;
import com.nequi.ticketing.domain.audit.AuditRecord;
import com.nequi.ticketing.domain.audit.AuditRecord.Actor;
import com.nequi.ticketing.domain.audit.AuditRecord.AuditCode;
import com.nequi.ticketing.domain.audit.AuditRecordBuilder;
import com.nequi.ticketing.domain.order.Order;
import com.nequi.ticketing.domain.order.ReversalPolicy;
import java.time.Instant;
import java.util.Objects;
import reactor.core.publisher.Mono;

/**
 * ADR-025 quarantine (AP-031), shared by every executor of a transition that can be cancelled by a
 * Ticket condition with the Order still in {@code CREATED}: CMP-005 ("Fail (enqueue)", IV-009), CMP-007
 * (consumer transitions, rule 15) and CMP-008 (expiration). The Order receives {@code quarantinedAt} and
 * {@code quarantineReason} with its {@code ORDER_QUARANTINED} audit; no Ticket is written and the active
 * Order lock is kept (ADR-032).
 */
final class OrderQuarantine {

    static final String ON_ENQUEUE_FAILURE = "TICKET_CONDITION_FAILED_ON_ENQUEUE_FAILURE";
    static final String ON_PAYMENT_START = "TICKET_CONDITION_FAILED_ON_PAYMENT_START";
    static final String ON_CONFIRMATION = "TICKET_CONDITION_FAILED_ON_CONFIRMATION";
    static final String ON_REJECTION = "TICKET_CONDITION_FAILED_ON_REJECTION";
    static final String ON_PROCESSING_FAILURE = "TICKET_CONDITION_FAILED_ON_PROCESSING_FAILURE";
    static final String ON_EXPIRATION = "TICKET_CONDITION_FAILED_ON_EXPIRATION";

    private final OrderLifecycleStore lifecycleStore;
    private final Clock clock;

    OrderQuarantine(OrderLifecycleStore lifecycleStore, Clock clock) {
        this.lifecycleStore = Objects.requireNonNull(lifecycleStore, "lifecycleStore");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * ADR-025 criterion, evaluated after re-reading the Order: the transaction was cancelled by the
     * condition of a Ticket, the Order guard itself held (the Order item is not among the failed
     * items) and the Order is still {@code CREATED} without quarantine.
     */
    static boolean applies(TransactionOutcome.Cancelled cancelled, OrderRecord reread) {
        boolean ticketCondition = cancelled.failed(FailedItem.TICKET_STATE) || cancelled.failed(FailedItem.TICKET_MISSING);
        return ticketCondition && !cancelled.failed(FailedItem.ORDER)
                && ReversalPolicy.quarantineOnTicketConditionFailure(reread.order());
    }

    /** AP-031 write; a condition that no longer holds is a {@code Cancelled} outcome. */
    Mono<TransactionOutcome> apply(Order current, String reason, Actor actor, String correlationId) {
        return Mono.defer(() -> {
            Instant quarantinedAt = clock.now();
            Order quarantined = current.quarantine(quarantinedAt, reason);
            return lifecycleStore.quarantine(new QuarantinePlan(current, quarantined, audit(quarantined, actor, correlationId)));
        });
    }

    static AuditRecord audit(Order quarantined, Actor actor, String correlationId) {
        return new AuditRecordBuilder()
                .code(AuditCode.ORDER_QUARANTINED)
                .event(quarantined.eventId())
                .order(quarantined.orderId())
                .tickets(quarantined.ticketIds())
                .cause(quarantined.quarantineReason())
                .actor(actor)
                .correlation(correlationId)
                .paymentAttempt(quarantined.paymentAttempt() == null ? null : quarantined.paymentAttempt().paymentAttemptId())
                .occurredAt(quarantined.quarantinedAt())
                .build();
    }
}
