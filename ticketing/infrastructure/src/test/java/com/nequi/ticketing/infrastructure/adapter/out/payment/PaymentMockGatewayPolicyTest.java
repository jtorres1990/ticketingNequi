package com.nequi.ticketing.infrastructure.adapter.out.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.nequi.ticketing.application.port.out.AuthorizationOutcome;
import com.nequi.ticketing.application.port.out.CancellationOutcome;
import com.nequi.ticketing.application.port.out.CancellationOutcome.CancellationStatus;
import com.nequi.ticketing.application.port.out.PaymentAuthorization;
import com.nequi.ticketing.infrastructure.adapter.out.payment.PaymentMockGatewayContractTest.RecordingPaymentEvents;
import com.nequi.ticketing.infrastructure.adapter.out.payment.PaymentTransport.HttpReply;
import com.nequi.ticketing.infrastructure.adapter.out.resilience.CircuitState;
import com.nequi.ticketing.infrastructure.adapter.sqs.VirtualClock;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.scheduler.VirtualTimeScheduler;

/**
 * Policy of CMP-013 and the Payment Mock circuit breaker of CMP-026 with the approved values (ADR-035,
 * ADR-008, IV-004) under virtual time, with a scripted transport: timeout per call, retries of transient
 * failures only, deadline, circuit shared by authorization and cancellation, slow calls, half-open probes.
 */
class PaymentMockGatewayPolicyTest {

    private static final String ATTEMPT = "6f1c2a8e-3b7d-4c9a-8e21-0d5f7b9a1c34-1";
    private static final PaymentAuthorization REQUEST = new PaymentAuthorization(ATTEMPT,
            "6f1c2a8e-3b7d-4c9a-8e21-0d5f7b9a1c34", "0f8fad5b-d9cb-469f-a165-70867728950e", "customer-sub-1",
            List.of("A-1-1"));

    private final VirtualTimeScheduler scheduler = VirtualTimeScheduler.create();
    private final VirtualClock clock = new VirtualClock(scheduler);
    private final ScriptedTransport transport = new ScriptedTransport();
    private final RecordingPaymentEvents events = new RecordingPaymentEvents();
    private final PaymentMockGateway gateway = PaymentMockGateway.create(transport,
            PaymentGatewaySettings.deployed(URI.create("http://payment-mock:8090"), "key"), clock, events, scheduler);

    @AfterEach
    void dispose() {
        scheduler.dispose();
    }

    @Test
    @DisplayName("ADR-035 IV-004 authorization: 3 s per call, 2 retries after 200..300 ms and 200..600 ms, then dependency unavailable")
    void authorizationTimeoutsAndRetries() {
        transport.authorizeWith(call -> Mono.never());
        AtomicReference<AuthorizationOutcome> outcome = new AtomicReference<>();
        Instant start = clock.now();

        gateway.authorize(REQUEST, start.plusSeconds(60)).subscribe(outcome::set);
        scheduler.advanceTimeBy(Duration.ofSeconds(20));

        List<Instant> calls = transport.authorizationTimes();
        assertThat(calls).hasSize(3);
        assertThat(calls.get(0)).isEqualTo(start);
        assertThat(Duration.between(calls.get(0), calls.get(1)))
                .isBetween(Duration.ofMillis(3_200), Duration.ofMillis(3_300));
        assertThat(Duration.between(calls.get(1), calls.get(2)))
                .isBetween(Duration.ofMillis(3_200), Duration.ofMillis(3_600));
        assertThat(outcome.get()).isEqualTo(new AuthorizationOutcome.DependencyUnavailable("TIMEOUT"));
        assertThat(transport.authorizationIds()).containsOnly(ATTEMPT);
        assertThat(events.failures()).containsExactly("authorize TIMEOUT", "authorize TIMEOUT", "authorize TIMEOUT");
    }

    @Test
    @DisplayName("ADR-008 ADR-035 the deadline cuts the retries and the call in flight: dependency unavailable at expiresAt minus the margin")
    void deadlineCutsTheAuthorization() {
        transport.authorizeWith(call -> Mono.never());
        AtomicReference<AuthorizationOutcome> outcome = new AtomicReference<>();
        Instant start = clock.now();

        gateway.authorize(REQUEST, start.plusSeconds(4)).subscribe(outcome::set);
        scheduler.advanceTimeBy(Duration.ofMillis(3_999));
        assertThat(outcome.get()).isNull();
        scheduler.advanceTimeBy(Duration.ofMillis(1));

        assertThat(outcome.get()).isEqualTo(new AuthorizationOutcome.DependencyUnavailable("DEADLINE_REACHED"));
        assertThat(transport.authorizationTimes()).hasSize(2);
        // the first call is cancelled by its 3 s timeout (a failure); the second one by the deadline (not a failure)
        assertThat(transport.cancelledCalls()).isEqualTo(2);
        assertThat(events.failures()).containsExactly("authorize TIMEOUT");
        assertThat(events.unavailable()).containsExactly("authorize DEADLINE_REACHED");
        scheduler.advanceTimeBy(Duration.ofSeconds(20));
        assertThat(transport.authorizationTimes()).hasSize(2);
    }

    @Test
    @DisplayName("ADR-008 a deadline already reached does not call the Payment Mock")
    void deadlineAlreadyReached() {
        AtomicReference<AuthorizationOutcome> outcome = new AtomicReference<>();

        gateway.authorize(REQUEST, clock.now()).subscribe(outcome::set);

        assertThat(outcome.get()).isEqualTo(new AuthorizationOutcome.DependencyUnavailable("DEADLINE_REACHED"));
        assertThat(transport.authorizationTimes()).isEmpty();
        assertThat(events.unavailable()).containsExactly("authorize DEADLINE_REACHED");
    }

    @Test
    @DisplayName("ADR-035 calls cut by the deadline are cancelled, not failures: 20 of them leave the circuit closed")
    void deadlineCutsAreNotFailures() {
        transport.authorizeWith(call -> Mono.never());
        for (int call = 0; call < 20; call++) {
            gateway.authorize(REQUEST, clock.now().plusSeconds(1)).subscribe();
            scheduler.advanceTimeBy(Duration.ofSeconds(1));
        }

        assertThat(transport.authorizationTimes()).hasSize(20);
        assertThat(gateway.circuit().state()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    @DisplayName("ADR-035 a decline and a contract error are never retried")
    void noRetryForResultsAndContractErrors() {
        transport.authorizeWith(call -> Mono.just(reply(200,
                "{\"paymentAttemptId\":\"" + ATTEMPT + "\",\"status\":\"DECLINED\",\"providerReference\":\"p\"}")));
        assertThat(authorize()).isEqualTo(new AuthorizationOutcome.Declined("p", null));
        transport.authorizeWith(call -> Mono.just(reply(422, "{\"code\":\"X\"}")));
        assertThat(authorize()).isEqualTo(new AuthorizationOutcome.ContractError(422));

        assertThat(transport.authorizationTimes()).hasSize(2);
    }

    @Test
    @DisplayName("ADR-038/mechanism circuit breaker: 50 % of transient failures over 10 calls opens the shared circuit; authorize and cancel are rejected without calls")
    void transientFailuresOpenTheSharedCircuit() {
        List<CircuitState> changes = new CopyOnWriteArrayList<>();
        gateway.circuit().onStateChange(changes::add);
        transport.cancelWith(call -> Mono.just(reply(503, "{\"code\":\"S\"}")));
        for (int call = 0; call < 10; call++) {
            assertThat(cancel()).isEqualTo(new CancellationOutcome.DependencyUnavailable("HTTP_503"));
        }
        assertThat(gateway.circuit().state()).isEqualTo(CircuitState.OPEN);
        assertThat(gateway.circuit().remainingOpenTime()).isEqualTo(Duration.ofSeconds(15));

        assertThat(authorize()).isEqualTo(new AuthorizationOutcome.DependencyUnavailable("CIRCUIT_OPEN"));
        assertThat(cancel()).isEqualTo(new CancellationOutcome.DependencyUnavailable("CIRCUIT_OPEN"));
        assertThat(transport.authorizationTimes()).isEmpty();
        assertThat(transport.cancellationTimes()).hasSize(10);
        assertThat(changes).containsExactly(CircuitState.OPEN);
        assertThat(events.unavailable()).endsWith("authorize CIRCUIT_OPEN", "cancel CIRCUIT_OPEN");
    }

    @Test
    @DisplayName("ADR-035 declines and 4xx contract errors do not open the circuit")
    void declinesAndContractErrorsDoNotOpen() {
        transport.authorizeWith(call -> Mono.just(reply(200,
                "{\"paymentAttemptId\":\"" + ATTEMPT + "\",\"status\":\"DECLINED\",\"providerReference\":\"p\","
                        + "\"reasonCode\":\"CARD_DECLINED\"}")));
        transport.cancelWith(call -> Mono.just(reply(400, "{\"code\":\"C\"}")));
        for (int call = 0; call < 20; call++) {
            assertThat(authorize()).isInstanceOf(AuthorizationOutcome.Declined.class);
            assertThat(cancel()).isEqualTo(new CancellationOutcome.ContractError(400));
        }

        assertThat(gateway.circuit().state()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    @DisplayName("ADR-035 50 % of calls longer than 2 s open the circuit; calls of 1.9 s do not")
    void slowCallsOpenTheCircuit() {
        transport.cancelWith(call -> Mono.delay(Duration.ofMillis(1_900), scheduler).thenReturn(cancelled("VOIDED")));
        for (int call = 0; call < 10; call++) {
            cancelAndAdvance(Duration.ofMillis(1_900));
        }
        assertThat(gateway.circuit().state()).isEqualTo(CircuitState.CLOSED);

        transport.cancelWith(call -> Mono.delay(Duration.ofMillis(2_500), scheduler).thenReturn(cancelled("VOIDED")));
        for (int call = 0; call < 10; call++) {
            cancelAndAdvance(Duration.ofMillis(2_500));
        }
        assertThat(gateway.circuit().state()).isEqualTo(CircuitState.OPEN);
    }

    @Test
    @DisplayName("ADR-038/mechanism circuit breaker: after 15 s open, 3 successful probes close it; a failed probe reopens it for 15 s")
    void halfOpenProbes() {
        transport.cancelWith(call -> Mono.just(reply(500, "{\"code\":\"S\"}")));
        for (int call = 0; call < 10; call++) {
            cancel();
        }
        assertThat(gateway.circuit().state()).isEqualTo(CircuitState.OPEN);
        scheduler.advanceTimeBy(Duration.ofSeconds(15));
        assertThat(cancel()).isEqualTo(new CancellationOutcome.DependencyUnavailable("CIRCUIT_OPEN"));
        scheduler.advanceTimeBy(Duration.ofMillis(1));

        assertThat(cancel()).isEqualTo(new CancellationOutcome.DependencyUnavailable("HTTP_500"));
        assertThat(gateway.circuit().state()).isEqualTo(CircuitState.HALF_OPEN);
        cancel();
        cancel();
        assertThat(gateway.circuit().state()).isEqualTo(CircuitState.OPEN);
        assertThat(gateway.circuit().remainingOpenTime()).isEqualTo(Duration.ofSeconds(15));

        scheduler.advanceTimeBy(Duration.ofSeconds(15).plusMillis(1));
        transport.cancelWith(call -> Mono.just(cancelled("REVERSED")));
        for (int probe = 0; probe < 3; probe++) {
            assertThat(cancel()).isEqualTo(new CancellationOutcome.Cancelled(CancellationStatus.REVERSED));
        }
        assertThat(gateway.circuit().state()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    @DisplayName("ADR-035 ADR-025 cancellation: a single call bounded by 3 s; the timeout is dependency unavailable")
    void cancellationTimeout() {
        transport.cancelWith(call -> Mono.never());
        AtomicReference<CancellationOutcome> outcome = new AtomicReference<>();

        gateway.cancel(ATTEMPT).subscribe(outcome::set);
        scheduler.advanceTimeBy(Duration.ofMillis(2_999));
        assertThat(outcome.get()).isNull();
        scheduler.advanceTimeBy(Duration.ofMillis(1));

        assertThat(outcome.get()).isEqualTo(new CancellationOutcome.DependencyUnavailable("TIMEOUT"));
        scheduler.advanceTimeBy(Duration.ofSeconds(30));
        assertThat(transport.cancellationTimes()).hasSize(1);
    }

    @Test
    @DisplayName("NFR-003 ADR-035 an unexpected error of the transport is a transient outcome, never an error signal")
    void unexpectedErrorsAreTransient() {
        transport.authorizeWith(call -> Mono.error(new IllegalStateException("boom")));
        transport.cancelWith(call -> Mono.error(new IllegalStateException("boom")));

        AtomicReference<AuthorizationOutcome> outcome = new AtomicReference<>();
        gateway.authorize(REQUEST, clock.now().plusSeconds(30)).subscribe(outcome::set);
        scheduler.advanceTimeBy(Duration.ofSeconds(5));
        assertThat(outcome.get()).isEqualTo(new AuthorizationOutcome.DependencyUnavailable("UNEXPECTED_ERROR"));
        assertThat(transport.authorizationTimes()).hasSize(3);
        assertThat(cancel()).isEqualTo(new CancellationOutcome.DependencyUnavailable("UNEXPECTED_ERROR"));
    }

    private AuthorizationOutcome authorize() {
        AtomicReference<AuthorizationOutcome> outcome = new AtomicReference<>();
        gateway.authorize(REQUEST, clock.now().plusSeconds(30)).subscribe(outcome::set);
        return outcome.get();
    }

    private CancellationOutcome cancel() {
        AtomicReference<CancellationOutcome> outcome = new AtomicReference<>();
        gateway.cancel(ATTEMPT).subscribe(outcome::set);
        return outcome.get();
    }

    private void cancelAndAdvance(Duration duration) {
        AtomicReference<CancellationOutcome> outcome = new AtomicReference<>();
        gateway.cancel(ATTEMPT).subscribe(outcome::set);
        scheduler.advanceTimeBy(duration);
        assertThat(outcome.get()).isEqualTo(new CancellationOutcome.Cancelled(CancellationStatus.VOIDED));
    }

    private static HttpReply reply(int status, String body) {
        return new HttpReply(status, body);
    }

    private static HttpReply cancelled(String status) {
        return reply(200, "{\"paymentAttemptId\":\"" + ATTEMPT + "\",\"cancellationStatus\":\"" + status + "\"}");
    }

    /** Scripted {@link PaymentTransport} that records the instant of every call on the virtual clock. */
    private final class ScriptedTransport implements PaymentTransport {

        private final List<Instant> authorizations = new CopyOnWriteArrayList<>();
        private final List<String> authorizationIds = new CopyOnWriteArrayList<>();
        private final List<Instant> cancellations = new CopyOnWriteArrayList<>();
        private final List<Integer> cancelled = new CopyOnWriteArrayList<>();
        private volatile Function<String, Mono<HttpReply>> authorize = call -> Mono.never();
        private volatile Function<String, Mono<HttpReply>> cancel = call -> Mono.never();

        void authorizeWith(Function<String, Mono<HttpReply>> behaviour) {
            authorize = behaviour;
        }

        void cancelWith(Function<String, Mono<HttpReply>> behaviour) {
            cancel = behaviour;
        }

        List<Instant> authorizationTimes() {
            return List.copyOf(authorizations);
        }

        List<String> authorizationIds() {
            return List.copyOf(authorizationIds);
        }

        List<Instant> cancellationTimes() {
            return List.copyOf(cancellations);
        }

        int cancelledCalls() {
            return cancelled.size();
        }

        @Override
        public Mono<HttpReply> authorize(String paymentAttemptId, String jsonBody) {
            authorizations.add(clock.now());
            authorizationIds.add(paymentAttemptId);
            return authorize.apply(jsonBody).doOnCancel(() -> cancelled.add(1));
        }

        @Override
        public Mono<HttpReply> cancel(String paymentAttemptId) {
            cancellations.add(clock.now());
            return cancel.apply(paymentAttemptId);
        }
    }
}
