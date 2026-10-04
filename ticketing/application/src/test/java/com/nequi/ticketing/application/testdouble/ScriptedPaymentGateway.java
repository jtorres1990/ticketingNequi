package com.nequi.ticketing.application.testdouble;

import com.nequi.ticketing.application.port.out.AuthorizationOutcome;
import com.nequi.ticketing.application.port.out.CancellationOutcome;
import com.nequi.ticketing.application.port.out.CancellationOutcome.CancellationStatus;
import com.nequi.ticketing.application.port.out.PaymentAuthorization;
import com.nequi.ticketing.application.port.out.PaymentGateway;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/**
 * Payment gateway double modelled on {@code payment-mock.openapi.v1.yaml} semantics (ADR-030): results
 * are idempotent per {@code paymentAttemptId} once final, the authorization outcomes are scripted in
 * order (default {@code APPROVED}), every authorization and cancellation is recorded, a cancellation
 * received before any final authorization makes later authorizations {@code DECLINED} with
 * {@code ATTEMPT_CANCELLED}, and authorizations can be held at a non-blocking gate or run a hook to
 * reproduce races deterministically.
 */
public final class ScriptedPaymentGateway implements PaymentGateway {

    private final Deque<AuthorizationOutcome> scriptedAuthorizations = new ArrayDeque<>();
    private final Deque<CancellationOutcome> scriptedCancellations = new ArrayDeque<>();
    private final Map<String, AuthorizationOutcome> finalResults = new ConcurrentHashMap<>();
    private final Map<String, CancellationStatus> cancellations = new ConcurrentHashMap<>();
    private final List<Authorization> authorizations = new CopyOnWriteArrayList<>();
    private final List<String> cancellationCalls = new CopyOnWriteArrayList<>();
    private final AtomicInteger waitingAuthorizations = new AtomicInteger();
    private volatile Sinks.Empty<Void> authorizationGate;
    private volatile Runnable onAuthorize = () -> { };

    @Override
    public Mono<AuthorizationOutcome> authorize(PaymentAuthorization request, Instant deadline) {
        return Mono.defer(() -> {
            waitingAuthorizations.incrementAndGet();
            Sinks.Empty<Void> gate = authorizationGate;
            Mono<Void> wait = gate == null ? Mono.empty() : gate.asMono().publishOn(Schedulers.parallel());
            return wait.then(Mono.fromCallable(() -> {
                authorizations.add(new Authorization(request, deadline));
                onAuthorize.run();
                return resolve(request.paymentAttemptId());
            }));
        });
    }

    @Override
    public Mono<CancellationOutcome> cancel(String paymentAttemptId) {
        return Mono.fromCallable(() -> {
            cancellationCalls.add(paymentAttemptId);
            CancellationOutcome scripted;
            synchronized (scriptedCancellations) {
                scripted = scriptedCancellations.poll();
            }
            if (scripted != null && !(scripted instanceof CancellationOutcome.Cancelled)) {
                return scripted;
            }
            CancellationStatus status = cancellations.computeIfAbsent(paymentAttemptId, id -> {
                AuthorizationOutcome result = finalResults.get(id);
                if (result instanceof AuthorizationOutcome.Approved) {
                    return CancellationStatus.REVERSED;
                }
                return result instanceof AuthorizationOutcome.Declined
                        ? CancellationStatus.VOIDED
                        : CancellationStatus.REGISTERED_BEFORE_CHARGE;
            });
            return new CancellationOutcome.Cancelled(status);
        });
    }

    private AuthorizationOutcome resolve(String paymentAttemptId) {
        AuthorizationOutcome stored = finalResults.get(paymentAttemptId);
        if (stored != null) {
            return stored;
        }
        if (cancellations.containsKey(paymentAttemptId)) {
            AuthorizationOutcome declined = new AuthorizationOutcome.Declined("ref-" + paymentAttemptId, "ATTEMPT_CANCELLED");
            finalResults.put(paymentAttemptId, declined);
            return declined;
        }
        AuthorizationOutcome next;
        synchronized (scriptedAuthorizations) {
            next = scriptedAuthorizations.poll();
        }
        if (next == null) {
            next = new AuthorizationOutcome.Approved("ref-" + paymentAttemptId);
        }
        if (next instanceof AuthorizationOutcome.Approved || next instanceof AuthorizationOutcome.Declined) {
            finalResults.put(paymentAttemptId, next);
        }
        return next;
    }

    /** Next authorizations (of any attempt without a final result) return these outcomes in order. */
    public void scriptAuthorizations(AuthorizationOutcome... outcomes) {
        synchronized (scriptedAuthorizations) {
            scriptedAuthorizations.addAll(List.of(outcomes));
        }
    }

    /** Next cancellations return these outcomes; a {@code Cancelled} entry uses the computed status. */
    public void scriptCancellations(CancellationOutcome... outcomes) {
        synchronized (scriptedCancellations) {
            scriptedCancellations.addAll(List.of(outcomes));
        }
    }

    /** Runs {@code hook} inside every authorization, before its result is returned. */
    public void onAuthorize(Runnable hook) {
        this.onAuthorize = hook;
    }

    public void holdAuthorizations() {
        authorizationGate = Sinks.empty();
    }

    public void releaseAuthorizations() {
        Sinks.Empty<Void> gate = authorizationGate;
        authorizationGate = null;
        if (gate != null) {
            gate.tryEmitEmpty();
        }
    }

    public int waitingAuthorizations() {
        return waitingAuthorizations.get();
    }

    public List<Authorization> authorizations() {
        return List.copyOf(authorizations);
    }

    public long authorizationsOf(String paymentAttemptId) {
        return authorizations.stream().filter(call -> call.request().paymentAttemptId().equals(paymentAttemptId)).count();
    }

    /** Distinct PaymentAttempt identifiers sent to the provider. */
    public List<String> attemptIds() {
        return authorizations.stream().map(call -> call.request().paymentAttemptId()).distinct().toList();
    }

    public List<String> cancellationCalls() {
        return List.copyOf(cancellationCalls);
    }

    /** One recorded authorization call with its deadline. */
    public record Authorization(PaymentAuthorization request, Instant deadline) {
    }
}
