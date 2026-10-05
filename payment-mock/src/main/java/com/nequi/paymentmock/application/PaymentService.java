package com.nequi.paymentmock.application;

import com.nequi.paymentmock.domain.AttemptPayload;
import com.nequi.paymentmock.domain.AuthorizationResult;
import com.nequi.paymentmock.domain.AuthorizationStep;
import com.nequi.paymentmock.domain.CancellationStep;
import com.nequi.paymentmock.domain.MockConfiguration;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/**
 * Payment operations consumed by the ticketing adapter: API-101 (authorization) and API-102 (cancellation). Every
 * invocation captures the current generation once (PM-IV-014) and applies its transition atomically per
 * {@code paymentAttemptId} (PM-SPK-005).
 *
 * <p>{@code LATENCY} (PM-IV-013, PM-SPK-004): the first invocation creates one shared decision that waits on the
 * injected scheduler (never blocking a thread) and then commits atomically: a cancellation registered meanwhile
 * wins ({@code ATTEMPT_CANCELLED}). The decision is subscribed independently of the caller, so it is committed even
 * if the caller disconnects; later invocations during the wait join it.
 */
public final class PaymentService {

    private final MockState state;
    private final Scheduler latencyScheduler;
    private final Clock clock;

    public PaymentService(MockState state, Scheduler latencyScheduler, Clock clock) {
        this.state = Objects.requireNonNull(state, "state");
        this.latencyScheduler = Objects.requireNonNull(latencyScheduler, "latencyScheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** API-101 for an authenticated request already validated against the contract. */
    public Mono<AuthorizationReply> authorize(String paymentAttemptId, AttemptPayload payload) {
        return Mono.defer(() -> {
            Generation generation = state.current();
            MockConfiguration configuration = generation.configuration();
            AuthorizationStep[] applied = new AuthorizationStep[1];
            AttemptEntry stored = generation.attempts().compute(paymentAttemptId, (id, existing) -> {
                AttemptEntry current = existing == null ? AttemptEntry.empty(id) : existing;
                AuthorizationStep step = current.attempt().authorize(payload, configuration);
                applied[0] = step;
                Mono<AuthorizationResult> pending = step instanceof AuthorizationStep.DecisionStarted started
                        ? decision(generation, id, started)
                        : current.pendingDecision();
                return new AttemptEntry(step.next(), pending);
            });
            return reply(generation, paymentAttemptId, applied[0], stored.pendingDecision());
        });
    }

    /** API-102: idempotent, valid whatever the state of the attempt (FG-003). */
    public Mono<CancellationReply> cancel(String paymentAttemptId) {
        return Mono.fromSupplier(() -> {
            Generation generation = state.current();
            Instant receivedAt = Instant.now(clock).truncatedTo(ChronoUnit.MILLIS);
            CancellationStep[] applied = new CancellationStep[1];
            generation.attempts().compute(paymentAttemptId, (id, existing) -> {
                AttemptEntry current = existing == null ? AttemptEntry.empty(id) : existing;
                CancellationStep step = current.attempt().cancel(receivedAt);
                applied[0] = step;
                return new AttemptEntry(step.next(), current.pendingDecision());
            });
            return new CancellationReply(paymentAttemptId, applied[0].status(), applied[0].replayed());
        });
    }

    /** Shared, lazily created decision; it is started once, after the atomic transition that created it. */
    private Mono<AuthorizationResult> decision(Generation generation, String paymentAttemptId,
            AuthorizationStep.DecisionStarted started) {
        return Mono.delay(Duration.ofMillis(started.addedLatencyMs()), latencyScheduler)
                .map(tick -> commit(generation, paymentAttemptId, started.planned()))
                .cache();
    }

    private static AuthorizationResult commit(Generation generation, String paymentAttemptId,
            AuthorizationResult planned) {
        AttemptEntry committed = generation.attempts().computeIfPresent(paymentAttemptId,
                (id, current) -> new AttemptEntry(current.attempt().completePending(planned), null));
        return Objects.requireNonNull(committed, "attempt of a pending decision").attempt().result();
    }

    private Mono<AuthorizationReply> reply(Generation generation, String paymentAttemptId, AuthorizationStep step,
            Mono<AuthorizationResult> pending) {
        return switch (step) {
            case AuthorizationStep.Replayed replayed -> Mono.just(new AuthorizationReply.Authorized(paymentAttemptId,
                    replayed.result(), true, replayed.next().cancelledAfterResult()));
            case AuthorizationStep.Decided decided -> Mono.just(new AuthorizationReply.Authorized(paymentAttemptId,
                    decided.result(), false, false));
            case AuthorizationStep.PayloadConflict ignored -> Mono.just(new AuthorizationReply.PayloadConflict());
            case AuthorizationStep.DefinitiveError ignored -> Mono.just(new AuthorizationReply.DefinitiveError());
            case AuthorizationStep.TransientFailure ignored -> Mono.just(new AuthorizationReply.TransientFailure());
            case AuthorizationStep.DecisionStarted ignored -> {
                // Decoupled from the caller: the decision completes even if this subscriber cancels.
                pending.subscribe();
                yield pending.map(result -> (AuthorizationReply) new AuthorizationReply.Authorized(paymentAttemptId,
                        result, false, false));
            }
            case AuthorizationStep.JoinPending ignored -> pending.map(result ->
                    new AuthorizationReply.Authorized(paymentAttemptId, result, true,
                            cancelledAfterResult(generation, paymentAttemptId)));
        };
    }

    private static boolean cancelledAfterResult(Generation generation, String paymentAttemptId) {
        AttemptEntry entry = generation.attempts().get(paymentAttemptId);
        return entry != null && entry.attempt().cancelledAfterResult();
    }
}
