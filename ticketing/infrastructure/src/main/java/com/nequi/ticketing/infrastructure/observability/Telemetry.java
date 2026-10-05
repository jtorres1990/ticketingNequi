package com.nequi.ticketing.infrastructure.observability;

import com.nequi.ticketing.application.port.in.GetEventAvailabilityUseCase;
import com.nequi.ticketing.application.port.in.ProcessOrderUseCase;
import com.nequi.ticketing.application.port.in.ProvisionEventUseCase;
import com.nequi.ticketing.application.port.in.StartPurchaseUseCase;
import com.nequi.ticketing.application.port.out.EventCatalog;
import com.nequi.ticketing.application.port.out.OrderLifecycleStore;
import com.nequi.ticketing.application.port.out.PaymentGateway;
import com.nequi.ticketing.application.port.out.TicketInventory;
import com.nequi.ticketing.infrastructure.adapter.in.scheduler.SchedulerEvents;
import com.nequi.ticketing.infrastructure.adapter.in.web.WebApiEvents;
import com.nequi.ticketing.infrastructure.adapter.out.dynamodb.DynamoDbEvents;
import com.nequi.ticketing.infrastructure.adapter.out.payment.PaymentEvents;
import com.nequi.ticketing.infrastructure.adapter.out.resilience.ManagedCircuitBreaker;
import com.nequi.ticketing.infrastructure.adapter.sqs.SqsEvents;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Tracer;
import java.util.Objects;

/**
 * CMP-018 Observability (NFR-005, EVAL-013; ADR-037, IV-007): the single entry point that turns the
 * notifications of the adapters (hooks) and the results of the ports (decorators) into
 * <ul>
 *   <li>Micrometer metrics, exported by the Prometheus endpoint of the internal management port: the technical
 *       and business metrics of {@code ticketing.aws-target.v2.md} §7 that the application produces (catalog in
 *       {@link MetricNames});</li>
 *   <li>structured logs written off the reactive threads ({@link LogDispatcher});</li>
 *   <li>spans continued from the {@code traceparent} of the messages ({@link Spans}).</li>
 * </ul>
 * Every hook and decorator records metrics with lock-free meters and never blocks (NFR-003); none of them
 * changes the result, the error or the timing of the port it observes. The bootstrap module creates one
 * instance per process and wires it into the adapters of the active role (CMP-021).
 */
public final class Telemetry {

    private final MeterRegistry registry;
    private final StructuredLog log;
    private final ObservabilitySettings settings;
    private final Spans spans;

    public Telemetry(MeterRegistry registry, LogDispatcher dispatcher, ObservabilitySettings settings, Tracer tracer) {
        this.registry = Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(dispatcher, "dispatcher");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.spans = new Spans(Objects.requireNonNull(tracer, "tracer"));
        this.log = new StructuredLog(dispatcher);
        FunctionCounter.builder(MetricNames.LOG_DROPPED, dispatcher, LogDispatcher::dropped)
                .description("Structured log records dropped because the log queue was full")
                .register(registry);
        Gauge.builder(MetricNames.LOG_PENDING, dispatcher, LogDispatcher::pending)
                .description("Structured log records waiting to be written")
                .register(registry);
    }

    public SqsEvents sqsEvents() {
        return new ObservedSqsEvents(this);
    }

    public PaymentEvents paymentEvents() {
        return new ObservedPaymentEvents(this);
    }

    public WebApiEvents webApiEvents() {
        return new ObservedWebApiEvents(this);
    }

    public SchedulerEvents schedulerEvents() {
        return new ObservedSchedulerEvents(this);
    }

    public DynamoDbEvents dynamoDbEvents() {
        return new ObservedDynamoDbEvents(this);
    }

    /** CMP-026: state gauge, transition counter and alarm log of one circuit breaker. */
    public void bindCircuit(String circuit, ManagedCircuitBreaker breaker) {
        CircuitTelemetry.bind(this, circuit, breaker);
    }

    public OrderLifecycleStore observeLifecycle(OrderLifecycleStore delegate) {
        return new ObservedOrderLifecycleStore(delegate, this);
    }

    public EventCatalog observeCatalog(EventCatalog delegate) {
        return new ObservedEventCatalog(delegate, this);
    }

    public TicketInventory observeInventory(TicketInventory delegate) {
        return new ObservedTicketInventory(delegate, this);
    }

    public PaymentGateway observePayments(PaymentGateway delegate) {
        return new ObservedPaymentGateway(delegate, this);
    }

    public StartPurchaseUseCase observePurchases(StartPurchaseUseCase delegate) {
        return new ObservedStartPurchase(delegate, this);
    }

    public GetEventAvailabilityUseCase observeAvailability(GetEventAvailabilityUseCase delegate) {
        return new ObservedAvailability(delegate, this);
    }

    public ProcessOrderUseCase observeOrderProcessing(ProcessOrderUseCase delegate) {
        return new ObservedProcessOrder(delegate, this);
    }

    public ProvisionEventUseCase observeProvisioning(ProvisionEventUseCase delegate) {
        return new ObservedProvisionEvent(delegate, this);
    }

    public MeterRegistry registry() {
        return registry;
    }

    public ObservabilitySettings settings() {
        return settings;
    }

    StructuredLog log() {
        return log;
    }

    Spans spans() {
        return spans;
    }

    Counter counter(String name, String... tags) {
        return registry.counter(name, tags);
    }

    Timer timer(String name, String... tags) {
        return Timer.builder(name).tags(tags).register(registry);
    }
}
