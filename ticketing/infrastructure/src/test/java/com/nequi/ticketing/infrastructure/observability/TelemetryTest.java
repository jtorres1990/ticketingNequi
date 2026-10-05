package com.nequi.ticketing.infrastructure.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nequi.ticketing.application.error.DependencyUnavailableException;
import com.nequi.ticketing.application.error.RequestRejectedException;
import com.nequi.ticketing.application.port.in.AvailabilityQuery;
import com.nequi.ticketing.application.port.in.AvailabilityView;
import com.nequi.ticketing.application.port.in.CycleResult;
import com.nequi.ticketing.application.port.in.Delivery;
import com.nequi.ticketing.application.port.in.DispositionReason;
import com.nequi.ticketing.application.port.in.GetEventAvailabilityUseCase;
import com.nequi.ticketing.application.port.in.ItemOutcome;
import com.nequi.ticketing.application.port.in.MessageDisposition;
import com.nequi.ticketing.application.port.in.ProcessOrderCommand;
import com.nequi.ticketing.application.port.in.ProcessOrderUseCase;
import com.nequi.ticketing.application.port.in.ProvisionEventCommand;
import com.nequi.ticketing.application.port.in.ProvisionEventUseCase;
import com.nequi.ticketing.application.port.in.PurchaseResult;
import com.nequi.ticketing.application.port.in.StartPurchaseCommand;
import com.nequi.ticketing.application.port.in.StartPurchaseUseCase;
import com.nequi.ticketing.application.port.out.AuthorizationOutcome;
import com.nequi.ticketing.application.port.out.CancellationOutcome;
import com.nequi.ticketing.application.port.out.CancellationOutcome.CancellationStatus;
import com.nequi.ticketing.application.port.out.PaymentAuthorization;
import com.nequi.ticketing.application.port.out.PaymentGateway;
import com.nequi.ticketing.domain.error.DomainErrorCode;
import com.nequi.ticketing.domain.error.ValidationException;
import com.nequi.ticketing.infrastructure.adapter.in.scheduler.PeriodicProcess;
import com.nequi.ticketing.infrastructure.adapter.out.dynamodb.DynamoDbEvents;
import com.nequi.ticketing.infrastructure.adapter.out.payment.PaymentEvents;
import com.nequi.ticketing.infrastructure.adapter.out.resilience.CircuitBreakerSettings;
import com.nequi.ticketing.infrastructure.adapter.out.resilience.ManagedCircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.test.simple.SimpleTracer;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** CMP-018 hooks, inbound decorators and CMP-026 circuit metrics. */
class TelemetryTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final LogDispatcher dispatcher = LogDispatcher.start(1_000);
    private final SimpleTracer tracer = new SimpleTracer();
    private final Telemetry telemetry = new Telemetry(registry, dispatcher, ObservabilitySettings.DEPLOYED, tracer);

    @AfterEach
    void closeDispatcher() {
        dispatcher.close(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("CMP-018 ADR-037 SQS, payment, web and DynamoDB notifications become metrics")
    void adapterHooks() {
        var sqs = telemetry.sqsEvents();
        sqs.publicationFailed("orders", new IllegalStateException("x"));
        sqs.unreadableMessage("orders", "m-1", "UNKNOWN_TYPE");
        sqs.processingFailed("provisioning", "m-2", new IllegalStateException("y"));
        sqs.messageActionFailed("orders", "m-3", new IllegalStateException("z"));
        sqs.receiveFailed("orders", new IllegalStateException("w"), Duration.ofSeconds(2));
        sqs.receiveFailed("orders", new IllegalStateException("w"), null);
        sqs.heartbeatStopped("provisioning", "m-4");
        assertThat(count(MetricNames.SQS_FAILURES, "queue", "orders", "kind", "receive")).isEqualTo(2);
        assertThat(count(MetricNames.SQS_FAILURES, "queue", "provisioning", "kind", "heartbeat_stopped")).isEqualTo(1);
        assertThat(count(MetricNames.SQS_FAILURES, "queue", "orders", "kind", "publication")).isEqualTo(1);

        var payments = telemetry.paymentEvents();
        payments.callFailed(PaymentEvents.AUTHORIZE, "o-1-1", "TIMEOUT");
        payments.dependencyUnavailable(PaymentEvents.CANCEL, "o-1-1", "CIRCUIT_OPEN");
        assertThat(count(MetricNames.PAYMENT_CALL_FAILURES, "operation", "authorize", "reason", "TIMEOUT")).isEqualTo(1);
        assertThat(count(MetricNames.PAYMENT_UNAVAILABLE, "operation", "cancel", "reason", "CIRCUIT_OPEN")).isEqualTo(1);

        var web = telemetry.webApiEvents();
        web.unclassifiedFailure("trace-1", new IllegalStateException("boom"));
        web.rateLimited("trace-2");
        assertThat(count(MetricNames.HTTP_UNCLASSIFIED)).isEqualTo(1);
        assertThat(count(MetricNames.HTTP_RATE_LIMITED)).isEqualTo(1);

        DynamoDbEvents dynamo = telemetry.dynamoDbEvents();
        dynamo.executed("TransactWriteItems", DynamoDbEvents.TRANSACTION_CONFLICT, Duration.ofMillis(7));
        assertThat(registry.get(MetricNames.DYNAMODB_REQUESTS).tags("operation", "TransactWriteItems",
                "outcome", "transaction_conflict").timer().count()).isEqualTo(1);
        DynamoDbEvents.NONE.executed("GetItem", DynamoDbEvents.SUCCESS, Duration.ZERO);
        assertThat(registry.find(MetricNames.LOG_DROPPED).functionCounter()).isNotNull();
        assertThat(registry.find(MetricNames.LOG_PENDING).gauge()).isNotNull();
        assertThat(telemetry.settings()).isEqualTo(ObservabilitySettings.DEPLOYED);
    }

    @Test
    @DisplayName("CMP-018 ADR-028 cycles, items, failures, overlaps and pauses of the periodic processes become metrics")
    void schedulerHooks() {
        var scheduler = telemetry.schedulerEvents();
        Map<ItemOutcome, Long> outcomes = new EnumMap<>(ItemOutcome.class);
        outcomes.put(ItemOutcome.EXPIRED, 3L);
        outcomes.put(ItemOutcome.QUARANTINED, 0L);
        scheduler.cycleCompleted(PeriodicProcess.EXPIRATION, "c-1", new CycleResult(false, outcomes), Duration.ofMillis(20));
        scheduler.cycleCompleted(PeriodicProcess.REPUBLISH, "c-2", CycleResult.skippedCycle(), Duration.ofMillis(1));
        scheduler.cycleFailed(PeriodicProcess.REVERSAL, "c-3", new IllegalStateException("x"), null, 2, Duration.ofSeconds(20));
        scheduler.cycleFailed(PeriodicProcess.REVERSAL, "c-4", null, new CycleResult(false,
                Map.of(ItemOutcome.FAILED, 1L)), 3, Duration.ofSeconds(40));
        scheduler.triggersSkipped(PeriodicProcess.PROVISIONING_CLEANUP, 2);
        scheduler.triggerPaused(PeriodicProcess.REVERSAL);

        assertThat(count(MetricNames.SCHEDULER_ITEMS, "process", "EXPIRATION", "outcome", "EXPIRED")).isEqualTo(3);
        assertThat(registry.find(MetricNames.SCHEDULER_ITEMS).tags("outcome", "QUARANTINED").counter()).isNull();
        assertThat(count(MetricNames.SCHEDULER_CYCLES, "process", "REPUBLISH", "outcome", "skipped")).isEqualTo(1);
        assertThat(count(MetricNames.SCHEDULER_CYCLES, "process", "REVERSAL", "outcome", "failed")).isEqualTo(2);
        assertThat(count(MetricNames.SCHEDULER_TRIGGERS_SKIPPED, "process", "PROVISIONING_CLEANUP", "reason", "overlap"))
                .isEqualTo(2);
        assertThat(count(MetricNames.SCHEDULER_TRIGGERS_SKIPPED, "process", "REVERSAL", "reason", "paused")).isEqualTo(1);
        assertThat(registry.get(MetricNames.SCHEDULER_CYCLE_DURATION).tags("process", "EXPIRATION").timer().count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("CMP-026 ADR-035 each circuit exposes its state gauge and counts its transitions")
    void circuitState() {
        Instant[] now = {Instant.parse("2026-01-01T00:00:00Z")};
        ManagedCircuitBreaker breaker = ManagedCircuitBreaker.create("payment-mock",
                new CircuitBreakerSettings(2, 2, 50f, Duration.ofSeconds(15), 1), () -> now[0], error -> true);
        telemetry.bindCircuit("payment-mock", breaker);
        assertThat(gauge("CLOSED")).isEqualTo(1.0);
        assertThat(gauge("OPEN")).isZero();

        for (int call = 0; call < 2; call++) {
            StepVerifier.create(Mono.error(new IllegalStateException("down"))
                    .transform(breaker.<Object>operator())).expectError().verify();
        }

        assertThat(gauge("OPEN")).isEqualTo(1.0);
        assertThat(gauge("CLOSED")).isZero();
        assertThat(count(MetricNames.CIRCUIT_TRANSITIONS, "circuit", "payment-mock", "to", "OPEN")).isEqualTo(1);
        now[0] = now[0].plusSeconds(16);
        StepVerifier.create(Mono.just("ok").transform(breaker.<String>operator())).expectNext("ok").verifyComplete();
        assertThat(count(MetricNames.CIRCUIT_TRANSITIONS, "circuit", "payment-mock", "to", "HALF_OPEN")).isEqualTo(1);
        assertThat(count(MetricNames.CIRCUIT_TRANSITIONS, "circuit", "payment-mock", "to", "CLOSED")).isEqualTo(1);
    }

    @Test
    @DisplayName("CMP-018 reservations created, replayed and rejected by code (aws-target §7)")
    void purchases() {
        StartPurchaseUseCase delegate = mock(StartPurchaseUseCase.class);
        PurchaseResult created = mock(PurchaseResult.class);
        PurchaseResult replayed = mock(PurchaseResult.class);
        when(replayed.replayed()).thenReturn(true);
        when(delegate.startPurchase(any()))
                .thenReturn(Mono.just(created), Mono.just(replayed),
                        Mono.error(RequestRejectedException.activeOrderExists()),
                        Mono.error(new ValidationException("ticketIds", "bad")),
                        Mono.error(new DependencyUnavailableException("down") {
                        }),
                        Mono.error(new IllegalStateException("unknown")));
        StartPurchaseUseCase observed = telemetry.observePurchases(delegate);
        StartPurchaseCommand command = mock(StartPurchaseCommand.class);

        StepVerifier.create(observed.startPurchase(command)).expectNext(created).verifyComplete();
        StepVerifier.create(observed.startPurchase(command)).expectNext(replayed).verifyComplete();
        for (int rejection = 0; rejection < 4; rejection++) {
            StepVerifier.create(observed.startPurchase(command)).expectError().verify();
        }

        assertThat(count(MetricNames.RESERVATIONS, "outcome", "created", "code", "none")).isEqualTo(1);
        assertThat(count(MetricNames.RESERVATIONS, "outcome", "replayed", "code", "none")).isEqualTo(1);
        assertThat(count(MetricNames.RESERVATIONS, "outcome", "rejected", "code", "ACTIVE_ORDER_EXISTS")).isEqualTo(1);
        assertThat(count(MetricNames.RESERVATIONS, "outcome", "rejected", "code", "VALIDATION_ERROR")).isEqualTo(1);
        assertThat(count(MetricNames.RESERVATIONS, "outcome", "rejected", "code", "SERVICE_UNAVAILABLE")).isEqualTo(1);
        assertThat(count(MetricNames.RESERVATIONS, "outcome", "rejected", "code", "INTERNAL_ERROR")).isEqualTo(1);
        assertThat(DomainErrorCode.VALIDATION_ERROR).isNotNull();
    }

    @Test
    @DisplayName("CMP-018 availability requests counted for the hit ratio of the count cache")
    void availability() {
        GetEventAvailabilityUseCase delegate = mock(GetEventAvailabilityUseCase.class);
        AvailabilityView view = mock(AvailabilityView.class);
        when(delegate.getAvailability(any())).thenReturn(Mono.just(view));
        StepVerifier.create(telemetry.observeAvailability(delegate).getAvailability(mock(AvailabilityQuery.class)))
                .expectNext(view).verifyComplete();
        assertThat(count(MetricNames.AVAILABILITY_REQUESTS)).isEqualTo(1);
    }

    @Test
    @DisplayName("CMP-018 ADR-037 Process Order runs in a span child of the message trace and counts its disposition")
    void orderProcessing() {
        ProcessOrderUseCase delegate = mock(ProcessOrderUseCase.class);
        when(delegate.process(any())).thenReturn(
                Mono.just(MessageDisposition.delete(DispositionReason.ALREADY_TERMINAL)),
                Mono.just(MessageDisposition.retry(DispositionReason.EXHAUSTED)),
                Mono.just(MessageDisposition.postponeUntil(Instant.now(), DispositionReason.LEASE_HELD_ELSEWHERE)),
                Mono.just(MessageDisposition.poison(DispositionReason.ENTITY_NOT_FOUND)));
        ProcessOrderUseCase observed = telemetry.observeOrderProcessing(delegate);
        ProcessOrderCommand command = ProcessOrderCommand.readable("o-1", "corr", new Delivery(1, false));

        for (int call = 0; call < 4; call++) {
            StepVerifier.create(observed.process(command)).expectNextCount(1).verifyComplete();
        }

        assertThat(count(MetricNames.MESSAGES_PROCESSED, "queue", "orders", "action", "delete", "reason",
                "ALREADY_TERMINAL")).isEqualTo(1);
        assertThat(count(MetricNames.MESSAGES_PROCESSED, "queue", "orders", "action", "retry", "reason", "EXHAUSTED"))
                .isEqualTo(1);
        assertThat(count(MetricNames.MESSAGES_PROCESSED, "queue", "orders", "action", "postpone", "reason",
                "LEASE_HELD_ELSEWHERE")).isEqualTo(1);
        assertThat(count(MetricNames.MESSAGES_PROCESSED, "queue", "orders", "action", "poison", "reason",
                "ENTITY_NOT_FOUND")).isEqualTo(1);
        assertThat(tracer.getSpans()).hasSize(4).allSatisfy(span -> assertThat(span.getName())
                .isEqualTo("ticketing.order.process"));
    }

    @Test
    @DisplayName("CMP-018 provisioning runs in a span, counts its disposition and records the duration of the run that enabled the Event")
    void provisioning() {
        ProvisionEventUseCase delegate = mock(ProvisionEventUseCase.class);
        when(delegate.provision(any())).thenReturn(Mono.just(MessageDisposition.delete(DispositionReason.ENABLED)),
                Mono.just(MessageDisposition.retry(DispositionReason.TRANSIENT_FAILURE)));
        ProvisionEventUseCase observed = telemetry.observeProvisioning(delegate);
        ProvisionEventCommand command = ProvisionEventCommand.readable("e-1", "corr", new Delivery(1, false), null);

        StepVerifier.create(observed.provision(command)).expectNextCount(1).verifyComplete();
        StepVerifier.create(observed.provision(command)).expectNextCount(1).verifyComplete();

        assertThat(registry.get(MetricNames.PROVISIONING_DURATION).timer().count()).isEqualTo(1);
        assertThat(count(MetricNames.MESSAGES_PROCESSED, "queue", "provisioning", "action", "retry", "reason",
                "TRANSIENT_FAILURE")).isEqualTo(1);
        assertThat(tracer.lastSpan().getName()).isEqualTo("ticketing.event.provision");
    }

    @Test
    @DisplayName("CMP-018 payment latency by operation and outcome; errors and empty results are unavailable and pass through")
    void paymentLatency() {
        PaymentGateway delegate = mock(PaymentGateway.class);
        when(delegate.authorize(any(), any())).thenReturn(
                Mono.just(new AuthorizationOutcome.Approved("p-1")),
                Mono.just(new AuthorizationOutcome.Declined("p-2", "INSUFFICIENT_FUNDS")),
                Mono.just(new AuthorizationOutcome.ContractError(422)),
                Mono.just(new AuthorizationOutcome.DependencyUnavailable("TIMEOUT")),
                Mono.error(new IllegalStateException("x")),
                Mono.empty());
        when(delegate.cancel(anyString())).thenReturn(
                Mono.just(new CancellationOutcome.Cancelled(CancellationStatus.REVERSED)),
                Mono.just(new CancellationOutcome.ContractError(400)),
                Mono.just(new CancellationOutcome.DependencyUnavailable("CIRCUIT_OPEN")));
        PaymentGateway observed = telemetry.observePayments(delegate);
        PaymentAuthorization request = mock(PaymentAuthorization.class);
        when(request.paymentAttemptId()).thenReturn("o-1-1");

        for (int call = 0; call < 4; call++) {
            StepVerifier.create(observed.authorize(request, Instant.now())).expectNextCount(1).verifyComplete();
        }
        StepVerifier.create(observed.authorize(request, Instant.now())).expectError(IllegalStateException.class).verify();
        StepVerifier.create(observed.authorize(request, Instant.now())).verifyComplete();
        for (int call = 0; call < 3; call++) {
            StepVerifier.create(observed.cancel("o-1-1")).expectNextCount(1).verifyComplete();
        }

        assertThat(timer("authorize", "approved")).isEqualTo(1);
        assertThat(timer("authorize", "declined")).isEqualTo(1);
        assertThat(timer("authorize", "contract_error")).isEqualTo(1);
        assertThat(timer("authorize", "unavailable")).isEqualTo(3);
        assertThat(timer("cancel", "cancelled")).isEqualTo(1);
        assertThat(timer("cancel", "contract_error")).isEqualTo(1);
        assertThat(timer("cancel", "unavailable")).isEqualTo(1);
        assertThat(tracer.getSpans()).extracting(span -> span.getName())
                .contains("ticketing.payment.authorize", "ticketing.payment.cancel");
    }

    @Test
    @DisplayName("CMP-018 the settings reject non-positive values")
    void settings() {
        assertThatThrownBy(() -> new ObservabilitySettings(Duration.ZERO, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ObservabilitySettings(Duration.ofSeconds(1), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(List.of(Dispositions.action(MessageDisposition.delete(DispositionReason.CONFIRMED)))).containsExactly("delete");
    }

    private double count(String name, String... tags) {
        return registry.get(name).tags(tags).counter().count();
    }

    private double gauge(String state) {
        return registry.get(MetricNames.CIRCUIT_STATE).tags("circuit", "payment-mock", "state", state).gauge().value();
    }

    private long timer(String operation, String outcome) {
        return registry.get(MetricNames.PAYMENT_DURATION).tags("operation", operation, "outcome", outcome).timer().count();
    }
}
