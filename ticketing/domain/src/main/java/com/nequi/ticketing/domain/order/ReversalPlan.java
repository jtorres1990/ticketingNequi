package com.nequi.ticketing.domain.order;

import static com.nequi.ticketing.domain.shared.DomainChecks.required;

import com.nequi.ticketing.domain.error.InvalidStateTransitionException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

public record ReversalPlan(
        String paymentAttemptId,
        int attempts,
        Instant requestedAt,
        Instant nextAttemptAt,
        boolean exhausted) {

    public static final int MAXIMUM_ATTEMPTS = 10;
    private static final List<Duration> DELAYS = List.of(
            Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofMinutes(1),
            Duration.ofMinutes(2), Duration.ofMinutes(5));

    public ReversalPlan {
        paymentAttemptId = required(paymentAttemptId, "paymentAttemptId");
        requestedAt = required(requestedAt, "requestedAt");
        nextAttemptAt = required(nextAttemptAt, "nextAttemptAt");
        if (attempts < 0 || attempts > MAXIMUM_ATTEMPTS || exhausted != (attempts == MAXIMUM_ATTEMPTS)) {
            throw new IllegalArgumentException("invalid reversal attempt state");
        }
    }

    public static ReversalPlan request(String paymentAttemptId, Instant now) {
        return new ReversalPlan(paymentAttemptId, 0, now, now.plus(delayAfter(0)), false);
    }

    public ReversalPlan reschedule(Instant now) {
        if (exhausted) {
            throw new InvalidStateTransitionException("an exhausted reversal cannot be rescheduled");
        }
        int nextAttempts = attempts + 1;
        boolean nextExhausted = nextAttempts == MAXIMUM_ATTEMPTS;
        Instant next = nextExhausted ? now : now.plus(delayAfter(nextAttempts));
        return new ReversalPlan(paymentAttemptId, nextAttempts, requestedAt, next, nextExhausted);
    }

    public static Duration delayAfter(int completedAttempts) {
        if (completedAttempts < 0 || completedAttempts >= MAXIMUM_ATTEMPTS) {
            throw new IllegalArgumentException("completedAttempts must be between 0 and 9");
        }
        return completedAttempts < DELAYS.size() ? DELAYS.get(completedAttempts) : Duration.ofMinutes(10);
    }
}
