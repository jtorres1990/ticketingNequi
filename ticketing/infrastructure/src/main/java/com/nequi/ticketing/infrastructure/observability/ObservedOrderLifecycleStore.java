package com.nequi.ticketing.infrastructure.observability;

import com.nequi.ticketing.application.port.out.ClosurePlan;
import com.nequi.ticketing.application.port.out.ConfirmationPlan;
import com.nequi.ticketing.application.port.out.EnqueueFailurePlan;
import com.nequi.ticketing.application.port.out.LateApprovalPlan;
import com.nequi.ticketing.application.port.out.OrderLifecycleStore;
import com.nequi.ticketing.application.port.out.PaymentLease;
import com.nequi.ticketing.application.port.out.PaymentStartPlan;
import com.nequi.ticketing.application.port.out.QuarantinePlan;
import com.nequi.ticketing.application.port.out.ReservationPlan;
import com.nequi.ticketing.application.port.out.ReversalCompletionPlan;
import com.nequi.ticketing.application.port.out.ReversalExhaustionPlan;
import com.nequi.ticketing.application.port.out.TransactionOutcome;
import com.nequi.ticketing.domain.order.Order;
import com.nequi.ticketing.domain.order.ReversalPlan;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import reactor.core.publisher.Mono;

/**
 * Business metrics of the Order lifecycle derived from the transitions the store applied (never from attempts):
 * terminal Orders by status and cause, expiration lag (ADR-028) and its threshold, Orders expired without a
 * PaymentAttempt, quarantines, late approvals and payment reversals requested, confirmed, rescheduled and
 * exhausted (ADR-025, ADR-037 alarm catalog). Results, errors and timing of the delegate are unchanged.
 */
final class ObservedOrderLifecycleStore implements OrderLifecycleStore {

    private final OrderLifecycleStore delegate;
    private final Telemetry telemetry;

    ObservedOrderLifecycleStore(OrderLifecycleStore delegate, Telemetry telemetry) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.telemetry = telemetry;
    }

    @Override
    public Mono<TransactionOutcome> reserve(ReservationPlan plan) {
        return delegate.reserve(plan);
    }

    @Override
    public Mono<Boolean> markEnqueued(String orderId, Instant enqueuedAt) {
        return delegate.markEnqueued(orderId, enqueuedAt);
    }

    @Override
    public Mono<TransactionOutcome> failEnqueue(EnqueueFailurePlan plan) {
        return delegate.failEnqueue(plan).doOnNext(outcome -> {
            if (applied(outcome)) {
                terminal(plan.failed());
            }
        });
    }

    @Override
    public Mono<TransactionOutcome> quarantine(QuarantinePlan plan) {
        return delegate.quarantine(plan).doOnNext(outcome -> {
            if (applied(outcome)) {
                telemetry.counter(MetricNames.ORDERS_QUARANTINED).increment();
                telemetry.log().warn("order.quarantined", "Order quarantined for manual review")
                        .with("orderId", plan.quarantined().orderId())
                        .with("eventId", plan.quarantined().eventId())
                        .with("reason", plan.quarantined().quarantineReason())
                        .with("correlationId", plan.audit().correlationId())
                        .write();
            }
        });
    }

    @Override
    public Mono<TransactionOutcome> startPayment(PaymentStartPlan plan) {
        return delegate.startPayment(plan);
    }

    @Override
    public Mono<Boolean> claimPaymentLease(String orderId, String paymentAttemptId, PaymentLease lease, Instant now) {
        return delegate.claimPaymentLease(orderId, paymentAttemptId, lease, now);
    }

    @Override
    public Mono<TransactionOutcome> confirm(ConfirmationPlan plan) {
        return delegate.confirm(plan).doOnNext(outcome -> {
            if (applied(outcome)) {
                terminal(plan.confirmed());
            }
        });
    }

    @Override
    public Mono<TransactionOutcome> close(ClosurePlan plan) {
        return delegate.close(plan).doOnNext(outcome -> {
            if (!applied(outcome)) {
                return;
            }
            terminal(plan.closed());
            if (plan.closed().reversalPlan() != null) {
                reversal("requested", plan.closed());
            }
            if (plan.kind() == ClosurePlan.Kind.EXPIRE) {
                expired(plan);
            }
        });
    }

    @Override
    public Mono<TransactionOutcome> recordLateApproval(LateApprovalPlan plan) {
        return delegate.recordLateApproval(plan).doOnNext(outcome -> {
            if (applied(outcome)) {
                telemetry.counter(MetricNames.LATE_APPROVALS).increment();
                if (plan.current().reversalPlan() == null && plan.updated().reversalPlan() != null) {
                    reversal("requested", plan.updated());
                }
            }
        });
    }

    @Override
    public Mono<TransactionOutcome> completeReversal(ReversalCompletionPlan plan) {
        return delegate.completeReversal(plan).doOnNext(outcome -> {
            if (applied(outcome)) {
                reversal("confirmed", plan.completed());
            }
        });
    }

    @Override
    public Mono<TransactionOutcome> exhaustReversal(ReversalExhaustionPlan plan) {
        return delegate.exhaustReversal(plan).doOnNext(outcome -> {
            if (applied(outcome)) {
                reversal("exhausted", plan.exhausted());
                telemetry.log().warn("payment.reversal.exhausted", "Payment reversal exhausted; manual review required")
                        .with("orderId", plan.exhausted().orderId())
                        .with("paymentAttemptId", plan.exhausted().reversalPlan().paymentAttemptId())
                        .with("attempts", plan.exhausted().reversalPlan().attempts())
                        .write();
            }
        });
    }

    @Override
    public Mono<Boolean> rescheduleReversal(String orderId, int expectedAttempts, ReversalPlan next) {
        return delegate.rescheduleReversal(orderId, expectedAttempts, next).doOnNext(rescheduled -> {
            if (Boolean.TRUE.equals(rescheduled)) {
                telemetry.counter(MetricNames.PAYMENT_REVERSALS, "outcome", "rescheduled").increment();
            }
        });
    }

    private void terminal(Order order) {
        telemetry.counter(MetricNames.ORDERS_TERMINAL, "status", order.status().name(),
                "cause", order.failureCause() == null ? "none" : order.failureCause().name()).increment();
    }

    private void reversal(String outcome, Order order) {
        telemetry.counter(MetricNames.PAYMENT_REVERSALS, "outcome", outcome).increment();
        if (!"exhausted".equals(outcome)) {
            telemetry.log().info("payment.reversal." + outcome, "Payment reversal " + outcome)
                    .with("orderId", order.orderId())
                    .with("paymentAttemptId", order.reversalPlan().paymentAttemptId())
                    .write();
        }
    }

    private void expired(ClosurePlan plan) {
        Order current = plan.current();
        Duration lag = Duration.between(current.reservation().expiresAt(), plan.now());
        Duration measured = lag.isNegative() ? Duration.ZERO : lag;
        telemetry.timer(MetricNames.EXPIRATION_LAG).record(measured);
        if (current.paymentAttempt() == null) {
            telemetry.counter(MetricNames.EXPIRED_WITHOUT_PAYMENT).increment();
        }
        if (measured.compareTo(telemetry.settings().expirationLagThreshold()) > 0) {
            telemetry.counter(MetricNames.EXPIRATION_LAG_EXCEEDED).increment();
            telemetry.log().warn("order.expiration.lag.exceeded", "Reservation released later than the target delay")
                    .with("orderId", current.orderId())
                    .with("eventId", current.eventId())
                    .with("lagMs", measured.toMillis())
                    .with("thresholdMs", telemetry.settings().expirationLagThreshold().toMillis())
                    .write();
        }
    }

    private static boolean applied(TransactionOutcome outcome) {
        return outcome instanceof TransactionOutcome.Applied;
    }
}
