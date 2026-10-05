package com.nequi.ticketing.domain.order;

import static com.nequi.ticketing.domain.shared.DomainChecks.required;

import com.nequi.ticketing.domain.error.InvalidStateTransitionException;
import java.time.Duration;
import java.time.Instant;

/**
 * Payment reversal mark of a terminal Order (FG-003, ADR-025). It is requested by the closing
 * transition, attempted by the reversal process, rescheduled after each transient failure following the
 * configurable {@link ReversalSchedule} (approved: 10 s, 30 s, 1 min, 2 min, 5 min and then every 10 min,
 * exhausted after ten failed attempts; IV-015) and completed once the provider confirms the cancellation.
 * It is a technical attribute, never a business state.
 */
public record ReversalPlan(
        String paymentAttemptId,
        int attempts,
        Instant requestedAt,
        Instant nextAttemptAt,
        boolean exhausted,
        Instant completedAt) {

    /** Approved maximum of attempts ({@link ReversalSchedule#DEPLOYED}). */
    public static final int MAXIMUM_ATTEMPTS = ReversalSchedule.DEPLOYED.maximumAttempts();

    public ReversalPlan {
        paymentAttemptId = required(paymentAttemptId, "paymentAttemptId");
        requestedAt = required(requestedAt, "requestedAt");
        nextAttemptAt = required(nextAttemptAt, "nextAttemptAt");
        // The maximum of attempts is configurable (IV-015): the stored mark tells whether it was exhausted.
        if (attempts < 0 || (exhausted && attempts < 1)) {
            throw new IllegalArgumentException("invalid reversal attempt state");
        }
        if (completedAt != null && exhausted) {
            throw new IllegalArgumentException("an exhausted reversal is left for manual review");
        }
    }

    public ReversalPlan(String paymentAttemptId, int attempts, Instant requestedAt, Instant nextAttemptAt,
            boolean exhausted) {
        this(paymentAttemptId, attempts, requestedAt, nextAttemptAt, exhausted, null);
    }

    /** Marks the reversal in the closing transaction; the first cancellation is due immediately. */
    public static ReversalPlan request(String paymentAttemptId, Instant now) {
        Instant requested = required(now, "now");
        return new ReversalPlan(paymentAttemptId, 0, requested, requested, false, null);
    }

    /** {@link #reschedule(Instant, ReversalSchedule)} with the approved schedule. */
    public ReversalPlan reschedule(Instant now) {
        return reschedule(now, ReversalSchedule.DEPLOYED);
    }

    /**
     * Records one more transient failure: the next attempt follows the schedule (approved: the first
     * failure waits 10 s); the failure that reaches its maximum of attempts exhausts the reversal for
     * manual review.
     */
    public ReversalPlan reschedule(Instant now, ReversalSchedule schedule) {
        required(schedule, "reversalSchedule");
        requirePending();
        if (exhausted) {
            throw new InvalidStateTransitionException("an exhausted reversal cannot be rescheduled");
        }
        int nextAttempts = attempts + 1;
        boolean nextExhausted = nextAttempts >= schedule.maximumAttempts();
        Instant current = required(now, "now");
        Instant next = nextExhausted ? current : current.plus(schedule.delayAfter(nextAttempts - 1));
        return new ReversalPlan(paymentAttemptId, nextAttempts, requestedAt, next, nextExhausted, null);
    }

    /** The provider confirmed the cancellation: the mark is removed (AP-030). */
    public ReversalPlan complete(Instant now) {
        requirePending();
        if (exhausted) {
            throw new InvalidStateTransitionException("an exhausted reversal is left for manual review");
        }
        return new ReversalPlan(paymentAttemptId, attempts, requestedAt, nextAttemptAt, false,
                required(now, "now"));
    }

    /** True while the reversal is marked (also when exhausted); false once completed. */
    public boolean pending() {
        return completedAt == null;
    }

    /** True when the reversal process must attempt the cancellation at {@code now}. */
    public boolean dueAt(Instant now) {
        return pending() && !exhausted && !nextAttemptAt.isAfter(required(now, "now"));
    }

    /** Approved backoff after the (index + 1)-th transient failure: index 0 is the first failure (10 s). */
    public static Duration delayAfter(int failureIndex) {
        return ReversalSchedule.DEPLOYED.delayAfter(failureIndex);
    }

    private void requirePending() {
        if (!pending()) {
            throw new InvalidStateTransitionException("the reversal is already completed");
        }
    }
}
