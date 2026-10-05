package com.nequi.ticketing.infrastructure.adapter.out.sqs;

import com.nequi.ticketing.application.port.out.Clock;
import com.nequi.ticketing.application.port.out.EventProvisioningRequested;
import com.nequi.ticketing.application.port.out.OrderProcessingRequested;
import com.nequi.ticketing.application.port.out.OrderQueuePublisher;
import com.nequi.ticketing.application.port.out.ProvisioningQueuePublisher;
import com.nequi.ticketing.application.port.out.PublishResult;
import com.nequi.ticketing.application.port.out.PublisherAvailability;
import com.nequi.ticketing.infrastructure.adapter.out.resilience.ManagedCircuitBreaker;
import com.nequi.ticketing.infrastructure.adapter.sqs.MessageCodec;
import com.nequi.ticketing.infrastructure.adapter.sqs.SqsEvents;
import com.nequi.ticketing.infrastructure.adapter.sqs.TraceContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

/**
 * CMP-011 SQS publisher adapter: one adapter with the two outbound ports of ADR-039, the Order queue
 * publisher (MSG-001) and the provisioning queue publisher (MSG-002), sharing the SQS publication circuit
 * breaker of ADR-035 (CMP-026).
 *
 * <p>Publication policy, from the inside out (ADR-035): timeout of 500 ms per attempt, circuit breaker,
 * retry of transient errors up to 3 attempts with exponential backoff and jitter, all inside a total budget
 * of 2 s (ADR-026). The SQS client has no SDK retries ({@code SqsClientFactory#forPublication}), so retries
 * are never stacked. A non-retryable error, the open circuit, the exhausted attempts or the exhausted budget
 * are a definitive failure, reported as {@link PublishResult#FAILED} and never as an error signal (the use
 * cases decide the compensation, ADR-026).
 */
public final class SqsQueuePublisher implements OrderQueuePublisher, ProvisioningQueuePublisher {

    static final String ORDERS = "orders";
    static final String PROVISIONING = "provisioning";

    private final SqsAsyncClient client;
    private final SqsPublisherSettings settings;
    private final ManagedCircuitBreaker circuit;
    private final Scheduler scheduler;
    private final SqsEvents events;

    private SqsQueuePublisher(SqsAsyncClient client, SqsPublisherSettings settings, ManagedCircuitBreaker circuit,
            Scheduler scheduler, SqsEvents events) {
        this.client = client;
        this.settings = settings;
        this.circuit = circuit;
        this.scheduler = scheduler;
        this.events = events;
    }

    public static SqsQueuePublisher create(SqsAsyncClient client, SqsPublisherSettings settings, Clock clock,
            SqsEvents events) {
        return create(client, settings, clock, events, Schedulers.parallel());
    }

    /** Variant with an explicit timer scheduler (virtual time in tests). */
    public static SqsQueuePublisher create(SqsAsyncClient client, SqsPublisherSettings settings, Clock clock,
            SqsEvents events, Scheduler scheduler) {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(scheduler, "scheduler");
        ManagedCircuitBreaker circuit = ManagedCircuitBreaker.create("sqs-publication", settings.circuit(), clock,
                SqsErrorClassification::isTransient);
        return new SqsQueuePublisher(client, settings, circuit, scheduler, events);
    }

    @Override
    public Mono<PublishResult> publish(OrderProcessingRequested message) {
        Objects.requireNonNull(message, "message");
        return Mono.deferContextual(context -> send(ORDERS, settings.ordersQueueUrl(),
                MessageCodec.encode(message, TraceContext.from(context))));
    }

    @Override
    public Mono<PublishResult> publish(EventProvisioningRequested message) {
        Objects.requireNonNull(message, "message");
        return Mono.deferContextual(context -> send(PROVISIONING, settings.provisioningQueueUrl(),
                MessageCodec.encode(message, TraceContext.from(context))));
    }

    /**
     * ADR-035: with the circuit open the purchase is rejected before reserving; {@code Unavailable} carries
     * the remaining open time. Half-open counts as available (the purchase follows the normal path).
     */
    @Override
    public PublisherAvailability availability() {
        return circuit.rejectingCalls()
                ? PublisherAvailability.unavailable(circuit.remainingOpenTime())
                : PublisherAvailability.available();
    }

    /** The publication circuit (state, remaining open time and state changes for metrics, INC-010). */
    public ManagedCircuitBreaker circuit() {
        return circuit;
    }

    private Mono<PublishResult> send(String queue, String queueUrl, MessageCodec.OutboundMessage message) {
        SendMessageRequest request = SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(message.body())
                .messageAttributes(attributes(message))
                .build();
        UnaryOperator<Mono<SendMessageResponse>> guarded = circuit.operator();
        Mono<SendMessageResponse> attempt = Mono.defer(() -> Mono.fromFuture(() -> client.sendMessage(request)))
                .timeout(settings.attemptTimeout(), scheduler)
                .transform(guarded);
        return attempt
                .retryWhen(Retry.backoff(settings.maxAttempts() - 1L, settings.backoffBase())
                        .jitter(settings.backoffJitter())
                        .scheduler(scheduler)
                        .filter(SqsErrorClassification::isTransient))
                .timeout(settings.budget(), scheduler)
                .thenReturn(PublishResult.PUBLISHED)
                .onErrorResume(error -> {
                    events.publicationFailed(queue, error);
                    return Mono.just(PublishResult.FAILED);
                });
    }

    private static Map<String, MessageAttributeValue> attributes(MessageCodec.OutboundMessage message) {
        Map<String, MessageAttributeValue> attributes = new LinkedHashMap<>();
        message.attributes().forEach((name, attribute) -> attributes.put(name, MessageAttributeValue.builder()
                .dataType(attribute.dataType())
                .stringValue(attribute.value())
                .build()));
        return attributes;
    }
}
